package com.netflix.spinnaker.tide.actor.copy

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryFailure, PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.TaskActorObject
import com.netflix.spinnaker.tide.actor.aws.{LoadBalancerActor, SecurityGroupActor}
import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor.{FoundTarget, RequiresSource, DependencyCopyTaskResult, DependencyCopyTask, VpcIds, CheckCompletion, SourceSecurityGroup, TargetSecurityGroup}
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.{LatestVpcs, GetVpcs}
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector._
import com.netflix.spinnaker.tide.actor.task.{TaskDirector, TaskActor, TaskProtocol}
import com.netflix.spinnaker.tide.config.AwsConfig
import com.netflix.spinnaker.tide.model.ResourceTracker.{SourceResource, TargetResource}
import com.netflix.spinnaker.tide.model._
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.transform.SecurityGroupConventions
import scala.concurrent.duration.DurationInt
import scala.collection.JavaConverters._
import scala.collection.mutable

class DependencyCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var checkForCreatedResources: Cancellable = _

  var vpcIds: VpcIds = _

  var task: DependencyCopyTask = _
  var taskId: String = _
  var resourceTracker: ResourceTracker = _
  var complete = false
  val elbExternalPorts: mutable.Set[Int] = mutable.Set()

  val clusterSharding = ClusterSharding.get(context.system)

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    val taskCluster = ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
    taskCluster ! taskEvent
  }

  def startChildTasks(childTaskDescriptions: ChildTaskDescriptions) = {
    ClusterSharding.get(context.system).shardRegion(TaskDirector.typeName) ! childTaskDescriptions
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  def findVpcId(vpcName: Option[String], vpcs: Seq[Vpc]): Option[String] = {
    vpcName match {
      case None => None
      case Some(name) =>
        val vpcId = vpcs.find(_.name == vpcName).map(_.vpcId)
        vpcId match {
          case None => throw new IllegalStateException(s"No VPC named '$name'.")
          case _ => vpcId
        }
    }
  }

  override def receiveCommand: Receive = {

    case ContinueTask(ExecuteTask(_, task: DependencyCopyTask, _)) =>
      checkForCreatedResources = scheduler.schedule(0 seconds, 15 seconds, self, CheckCompletion())

    case event @ ExecuteTask(_, _: DependencyCopyTask, _) =>
      persist(event) { e =>
        updateState(e)
        clusterSharding.shardRegion(VpcPollingActor.typeName) ! GetVpcs(task.target.location)
      }

    case latestVpcs: LatestVpcs =>
      val sourceVpcId = findVpcId(task.source.vpcName, latestVpcs.vpcs)
      val targetVpcId = findVpcId(task.target.vpcName, latestVpcs.vpcs)
      persist(VpcIds(sourceVpcId, targetVpcId)) { it =>
        updateState(it)
        startResourceCopying()
      }

    case event: TaskComplete =>
      persist(event) { it =>
        updateState(it)
        checkForCreatedResources.cancel()
        sendTaskEvent(it)
        if (!task.dryRun) {
          val logMessage = event match {
            case taskSuccess: TaskSuccess => "All resources copied successfully."
            case taskFailure: TaskFailure => s"Failure: ${taskFailure.message}"
          }
          sendTaskEvent(Log(taskId, logMessage))
        }
      }

    case event: CheckCompletion =>
      val notYetFound = resourceTracker.dependenciesNotYetFound.map(_.ref.identity.akkaIdentifier)
      if (notYetFound.nonEmpty) {
        sendTaskEvent(Log(taskId, s"Waiting for dependencies: ${notYetFound.mkString(", ")}"))
      }
      checkCompletion()
      askForRelevantResources()

    case event: RequiresSource =>
      if (!resourceTracker.isRequiredSource(event.source)) {
        persist(event) { e =>
          updateState(e)
          checkForCreatedTargetResource(resourceTracker.transformToTarget(e.source))
        }
      }

    case event: FoundTarget =>
      if (!resourceTracker.isFoundTarget(event.target)) {
        persist(event) { e =>
          updateState(e)
          checkCompletion()
        }
      }

    case event: LoadBalancerDetails =>
      resourceTracker.asResource(event.awsReference) match {
        case resource: TargetResource[LoadBalancerIdentity] =>
          event.latestState match {
            case None =>
              val sourceResource = resourceTracker.getSourceByTarget(resource)
              clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(sourceResource.ref, GetLoadBalancer())
            case Some(latestState) =>
              self ! FoundTarget(resource)
          }
        case resource: SourceResource[LoadBalancerIdentity] =>
          event.latestState match {
            case None => Nil
            case Some(latestState) =>
              if (vpcIds.target.isDefined) {
                latestState.state.securityGroups.foreach { name =>
                  val referencedSourceSecurityGroup = resourceTracker.asSourceSecurityGroupReference(name)
                  self ! RequiresSource(referencedSourceSecurityGroup, Some(resource.ref))
                }
              }
              elbExternalPorts ++= latestState.state.listenerDescriptions.map { it => it.listener.loadBalancerPort }
              val elbSecurityGroup : String = SecurityGroupConventions.appSecurityGroupForElbName(task.appName.get)
              self ! RequiresSource(resourceTracker.asSourceSecurityGroupReference(elbSecurityGroup), None)

              val targetResource = resourceTracker.transformToTarget(resource)
              val referencingSource = resourceTracker.lookupReferencingSourceByTarget(targetResource)
              sendTaskEvent(Mutation(taskId, Create(), CreateAwsResource(targetResource.ref, referencingSource)))
              if (task.dryRun) {
                self ! FoundTarget(targetResource)
              } else {
                val loadBalancerStateForTargetVpc = latestState.state.forVpc(task.target.vpcName, vpcIds.target)
                  .removeLegacySuffixesFromSecurityGroups()
                val newLoadBalancerState = (vpcIds.target, task.appName) match {
                  case (Some(_), Some(appName)) =>
                    val securityGroupsIncludingAppElbSecurityGroup = loadBalancerStateForTargetVpc.securityGroups +
                      SecurityGroupConventions.appSecurityGroupForElbName(appName)
                    loadBalancerStateForTargetVpc.copy(securityGroups = securityGroupsIncludingAppElbSecurityGroup)
                  case _ => loadBalancerStateForTargetVpc
                }
                val upsert = UpsertLoadBalancer(newLoadBalancerState, overwrite = false)
                logCreateEvent(targetResource, referencingSource)
                clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
              }
          }
        case other => Nil
      }

    case event: SecurityGroupDetails =>
      resourceTracker.asResource(event.awsReference) match {
        case resource: TargetResource[SecurityGroupIdentity] =>
          findSourceForTargetSecurityGroup(event.latestState, resource)
        case resource: SourceResource[SecurityGroupIdentity] =>
          copySourceSecurityGroup(event.latestState, resource)
        case other => Nil
      }

  }

  private def startResourceCopying(): Unit = {
    checkForCreatedResources = scheduler.schedule(15 seconds, 15 seconds, self, CheckCompletion())
    task.requiredSecurityGroupNames.foreach { it =>
      self ! RequiresSource(resourceTracker.asSourceSecurityGroupReference(it), None)
    }
    task.sourceLoadBalancerNames.foreach { it =>
      self ! RequiresSource(resourceTracker.asSourceLoadBalancerReference(it), None)
    }
  }

  def findSourceForTargetSecurityGroup(latestStateOption: Option[SecurityGroupLatestState],
                                       resource: TargetResource[SecurityGroupIdentity]): Unit = {
    latestStateOption match {
      case None =>
        if (task.dryRun) {
          persist(TargetSecurityGroup(resource, "nonexistent"))(it => updateState(it))
        }
      case Some(latestState) =>
        persist(TargetSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
        self ! FoundTarget(resource)
    }
    val sourceResource = resourceTracker.getSourceByTarget(resource)
    clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(sourceResource.ref, GetSecurityGroup())
  }

  def copySourceSecurityGroup(latestStateOption: Option[SecurityGroupLatestState],
                              resource: SourceResource[SecurityGroupIdentity]): Unit = {
    val groupName = resource.ref.identity.groupName
    val desiredState: SecurityGroupState = latestStateOption match {
      case None =>
        persist(SourceSecurityGroup(resource, "nonexistent"))(it => updateState(it))
        SecurityGroupState(groupName, Set())
      case Some(latestState) =>
        persist(SourceSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
        latestState.state
    }
    if (!groupName.startsWith("nf-")) {
      val targetResource = resourceTracker.transformToTarget(resource)
      val referencingSource = resourceTracker.lookupReferencingSourceByTarget(targetResource)
      val newIpPermissions = constructIngressForNewSecurityGroup(groupName, desiredState)
      sendTaskEvent(Mutation(taskId, Create(), CreateAwsResource(targetResource.ref, referencingSource)))
      if (task.dryRun) {
        self ! FoundTarget(targetResource)
      } else {
        requireIngressSecurityGroups(newIpPermissions, targetResource.ref)
        val upsert: UpsertSecurityGroup = constructUpsertForNewSecurityGroup(
          desiredState.copy(ipPermissions = newIpPermissions))
        logCreateEvent(targetResource, referencingSource)
        clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
      }
    }
  }

  private def constructUpsertForNewSecurityGroup(desiredState: SecurityGroupState): UpsertSecurityGroup = {
    val desiredStateSafeDescription = desiredState.copy(
      description = desiredState.description.replaceAll("[^A-Za-z0-9. _-]", "")
    )
    val newSecurityGroupState = desiredStateSafeDescription.removeLegacySuffixesFromSecurityGroupIngressRules()
    val upsert = UpsertSecurityGroup(newSecurityGroupState, overwrite = true)
    upsert
  }

  def constructIngressForNewSecurityGroup(groupName: String, securityGroupState: SecurityGroupState): Set[IpPermission] = {
    if (task.skipAllIngress ||
      (groupName != SecurityGroupConventions.appSecurityGroupForElbName(task.appName.get)
        && !task.requiredSecurityGroupNames.contains(groupName))) {
      return Set()
    }
    val spreadUserIdGroupPairs = AwsConversion.spreadUserIdGroupPairIngress(securityGroupState.ipPermissions)
    val targetIsVpc = resourceTracker.vpcIds.target.isDefined
    lazy val targetSecurityGroups = {
      val ec2 = AwsConfig.awsServiceProviderFactory.getAwsServiceProvider(resourceTracker.target.location).get.getAmazonEC2
      ec2.describeSecurityGroups().getSecurityGroups.asScala
    }
    val filteredSecurityGroupIngress = spreadUserIdGroupPairs.filter { ipPermission =>
      val pair = ipPermission.userIdGroupPairs.head
      if (pair.account.id == "amazon-elb") {
        false
      } else if (pair.account.name.isDefined) {
        if (pair.groupName.isDefined && pair.groupName.get.startsWith("nf-")) {
          targetSecurityGroups.exists { sg =>
            sg.getGroupName == pair.groupName.get && (if (targetIsVpc) sg.getVpcId == resourceTracker.vpcIds.target.get else sg.getVpcId == null)
          }
        } else {
          true
        }
      } else {
        false
      }
    }
    val securityGroupIngress = for (
      permission <- filteredSecurityGroupIngress;
      userIdGroupPair <- permission.userIdGroupPairs
    ) yield permission.copy(userIdGroupPairs = Set(userIdGroupPair.copy(vpcName = task.target.vpcName)))
    val ipIngress = AwsConversion.spreadIpRangeIngress(securityGroupState.ipPermissions)
    var newIngress = securityGroupIngress ++ ipIngress
    if (groupName == SecurityGroupConventions.appSecurityGroupForElbName(task.appName.get)) {
      newIngress = newIngress ++ buildMappingsForElbPorts()
    }
    task.appName match {
      case Some(appName) =>
        newIngress ++ SecurityGroupConventions(appName, task.target.location.account, task.target.vpcName).
          appendBoilerplateIngress(groupName, task.allowIngressFromClassic, task.sourceLoadBalancerNames.nonEmpty)
      case _ =>
        newIngress
    }
  }

  private def buildMappingsForElbPorts(): Set[IpPermission] = {
    elbExternalPorts.map { it =>
      IpPermission(
        fromPort = Some(it),
        toPort = Some(it),
        ipProtocol = "tcp",
        ipRanges = Set("0.0.0.0/0"),
        userIdGroupPairs = Set()
      )
    }.toSet
  }

  private def requireIngressSecurityGroups(ingress: Set[IpPermission], referencedBy: AwsReference[SecurityGroupIdentity]): Unit = {
    for (
      ipPermission <- ingress;
      userIdGroupPair <- ipPermission.userIdGroupPairs
    ) {
      val ingressGroupName = userIdGroupPair.groupName.get
      val groupName = referencedBy.identity.groupName
      val targetAccount = task.target.location.account
      userIdGroupPair.account.name match {
        case Some(ingressAccountName) if targetAccount == ingressAccountName =>
          val referencedSourceSecurityGroup = resourceTracker.asSourceSecurityGroupReference(ingressGroupName)
          self ! RequiresSource(referencedSourceSecurityGroup, Option(referencedBy))
        case Some(ingressAccountName) =>
          val dependencyCopyTask = DependencyCopyTask(
            VpcLocation(task.source.location.copy(account = ingressAccountName), task.source.vpcName),
            VpcLocation(task.target.location.copy(account = targetAccount), task.target.vpcName),
            Set(ingressGroupName),
            Set(), None, allowIngressFromClassic = task.allowIngressFromClassic, dryRun = task.dryRun,
            skipAllIngress = true)
          startChildTasks(ChildTaskDescriptions(taskId, List(dependencyCopyTask)))
        case None =>
          val msg = s"Cannot construct cross account security group ingress: ($targetAccount.$groupName from ${userIdGroupPair.account.id}.$ingressGroupName)"
          sendTaskEvent(Log(taskId, msg))
      }
    }

  }

  def logCreateEvent(targetResource: TargetResource[_ <: AwsIdentity], referencingSource: Option[AwsReference[_]]): Unit = {
    val reasonMsg = referencingSource match {
      case None => ""
      case Some(source) => s" because of reference from ${source.akkaIdentifier}."
    }
    sendTaskEvent(Log(taskId, s"Updating ${targetResource.ref.identity.akkaIdentifier}$reasonMsg."))
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, copyTask: DependencyCopyTask, _) =>
        taskId = newTaskId
        task = copyTask
      case event: TaskComplete =>
        complete = true
      case event: VpcIds =>
        vpcIds = event
        resourceTracker = ResourceTracker(task.source, task.target, vpcIds)
      case event: TaskComplete =>
      case RequiresSource(source, referencedBy) =>
        resourceTracker.requiredSource(source, referencedBy)
      case FoundTarget(target) =>
        resourceTracker.foundTarget(target)
      case event: SourceSecurityGroup =>
        resourceTracker.addSourceSecurityGroupId(event.source, event.id)
      case event: TargetSecurityGroup =>
        resourceTracker.addTargetSecurityGroupId(event.target, event.id)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case msg: RecoveryFailure => log.error(msg.cause, msg.cause.toString)
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }

  def checkCompletion() = {
    if (resourceTracker.hasResolvedEverythingRequired) {
      self ! TaskSuccess(taskId, task, DependencyCopyTaskResult(resourceTracker.securityGroupIdsSourceToTarget,
        resourceTracker.targetSecurityGroupNameToId))
    }
  }

  def askForRelevantResources(): Unit = {
    resourceTracker.dependenciesNotYetFound.foreach(checkForCreatedTargetResource)
    resourceTracker.unseenSourceSecurityGroupReferences.foreach { unseenSourceSecurityGroup =>
      clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(unseenSourceSecurityGroup, GetSecurityGroup())
    }
  }

  def checkForCreatedTargetResource(targetResource: TargetResource[_ <: AwsIdentity]): Unit = {
    targetResource.ref.identity match {
      case identity: SecurityGroupIdentity =>
        clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(targetResource.ref, GetSecurityGroup())
      case identity: LoadBalancerIdentity =>
        clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(targetResource.ref, GetLoadBalancer())
    }
  }

}

sealed trait DependencyCopyProtocol extends Serializable

object DependencyCopyActor extends TaskActorObject {
  val props = Props[DependencyCopyActor]

  case class DependencyCopyTaskResult(securityGroupIdSourceToTarget: Map[String, String],
                                      targetSecurityGroupNameToId: Map[String, String]) extends TaskResult with DependencyCopyProtocol
  case class DependencyCopyTask(source: VpcLocation,
                                target: VpcLocation,
                                requiredSecurityGroupNames: Set[String],
                                sourceLoadBalancerNames: Set[String],
                                appName: Option[String] = None,
                                skipAllIngress: Boolean = false,
                                allowIngressFromClassic: Boolean = true,
                                dryRun: Boolean = false) extends TaskDescription with DependencyCopyProtocol {
    val taskType: String = "DependencyCopyTask"
    val executionActorTypeName: String = typeName
    override def summary: String = s"Dependency copy from $source to $target (requiredSecurityGroupNames: $requiredSecurityGroupNames, sourceLoadBalancerNames: $sourceLoadBalancerNames, dryRun: $dryRun)."
  }
  case class VpcIds(source: Option[String], target: Option[String]) extends DependencyCopyProtocol
  case class CheckCompletion() extends DependencyCopyProtocol

  case class RequiresSource(source: SourceResource[_ <: AwsIdentity], referencedBy: Option[AwsReference[_ <: AwsIdentity]]) extends DependencyCopyProtocol
  case class NonexistentTarget(target: TargetResource[_ <: AwsIdentity]) extends DependencyCopyProtocol
  case class FoundTarget(target: TargetResource[_ <: AwsIdentity]) extends DependencyCopyProtocol

  case class SourceSecurityGroup(source: SourceResource[SecurityGroupIdentity], id: String) extends DependencyCopyProtocol
  case class TargetSecurityGroup(target: TargetResource[SecurityGroupIdentity], id: String) extends DependencyCopyProtocol

}
