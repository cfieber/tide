package com.netflix.spinnaker.tide.actor.copy

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryFailure, PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.TaskActorObject
import com.netflix.spinnaker.tide.actor.aws.{LoadBalancerActor, SecurityGroupActor}
import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor.{FoundTarget, RequiresSource, NonexistentTarget, DependencyCopyTaskResult, DependencyCopyTask, VpcIds, CheckCompletion, SourceSecurityGroup, TargetSecurityGroup}
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.{LatestVpcs, GetVpcs}
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector._
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskProtocol}
import com.netflix.spinnaker.tide.model.ResourceTracker.{SourceResource, TargetResource}
import com.netflix.spinnaker.tide.model._
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.transform.{SecurityGroupConventions, VpcTransformations}
import scala.concurrent.duration.DurationInt

class DependencyCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var checkForCreatedResources: Cancellable = _

  var task: DependencyCopyTask = _
  var vpcIds: VpcIds = _
  var taskId: String = _
  var resourceTracker: ResourceTracker = _
  var complete = false

  val clusterSharding = ClusterSharding.get(context.system)

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    val taskCluster = ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
    taskCluster ! taskEvent
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
      checkForCreatedResources = scheduler.schedule(0 seconds, 60 seconds, self, CheckCompletion())

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
        checkForCreatedResources = scheduler.schedule(60 seconds, 60 seconds, self, CheckCompletion())
        val requiredSecurityGroupNames = (targetVpcId, task.appName) match {
          case (Some(_), Some(appName)) =>
            val appSecurityGroupNames = Seq(appName, SecurityGroupConventions(appName).appSecurityGroupForElbName)
            task.requiredSecurityGroupNames ++ appSecurityGroupNames
          case _ =>
            task.requiredSecurityGroupNames
        }
        requiredSecurityGroupNames.foreach { it =>
          self ! RequiresSource(resourceTracker.asSourceSecurityGroupReference(it), None)
        }
        task.sourceLoadBalancerNames.foreach { it =>
          self ! RequiresSource(resourceTracker.asSourceLoadBalancerReference(it), None)
        }
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

    case event: NonexistentTarget =>
      if (!resourceTracker.isNonexistentTarget(event.target)) {
        persist(event) { e =>
          updateState(e)
        }
      }

    case event: LoadBalancerDetails =>
      resourceTracker.asResource(event.awsReference) match {
        case resource: TargetResource[LoadBalancerIdentity] =>
          event.latestState match {
            case None =>
              self ! NonexistentTarget(resource)
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
              val targetResource = resourceTracker.transformToTarget(resource)
              val referencingSource = resourceTracker.lookupReferencingSourceByTarget(targetResource)
              if (resourceTracker.isNonexistentTarget(targetResource)) {
                sendTaskEvent(Mutation(taskId, Create(), CreateAwsResource(targetResource.ref, referencingSource)))
                if (task.dryRun) {
                  self ! FoundTarget(targetResource)
                } else {
                  val loadBalancerStateForTargetVpc = latestState.state.forVpc(task.target.vpcName, vpcIds.target)
                    .removeLegacySuffixesFromSecurityGroups()
                  val newLoadBalancerState = (vpcIds.target, task.appName) match {
                    case (Some(_), Some(appName)) =>
                      val securityGroupsIncludingAppElbSecurityGroup = loadBalancerStateForTargetVpc.securityGroups +
                        SecurityGroupConventions(appName).appSecurityGroupForElbName
                      loadBalancerStateForTargetVpc.copy(securityGroups = securityGroupsIncludingAppElbSecurityGroup)
                    case _ => loadBalancerStateForTargetVpc
                  }
                  val upsert = UpsertLoadBalancer(newLoadBalancerState, overwrite = false)
                  logCreateEvent(targetResource, referencingSource)
                  clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
                }
              }
          }
        case other => Nil
      }

    case event: SecurityGroupDetails if !complete =>
      resourceTracker.asResource(event.awsReference) match {
        case resource: TargetResource[SecurityGroupIdentity] =>
          findSourceForTargetSecurityGroup(event.latestState, resource)
        case resource: SourceResource[SecurityGroupIdentity] =>
          copySourceSecurityGroup(event.latestState, resource)
        case other => Nil
      }

  }

  def findSourceForTargetSecurityGroup(latestStateOption: Option[SecurityGroupLatestState],
                                       resource: TargetResource[SecurityGroupIdentity]): Unit = {
    latestStateOption match {
      case None =>
        if (task.dryRun) {
          persist(TargetSecurityGroup(resource, "nonexistant"))(it => updateState(it))
        }
        self ! NonexistentTarget(resource)
      case Some(latestState) =>
        persist(TargetSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
        if (latestState.state.ipPermissions.isEmpty) {
          self ! NonexistentTarget(resource)
          self ! FoundTarget(resource)
        } else {
          self ! FoundTarget(resource)
        }
    }
    val sourceResource = resourceTracker.getSourceByTarget(resource)
    clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(sourceResource.ref, GetSecurityGroup())
  }

  def copySourceSecurityGroup(latestStateOption: Option[SecurityGroupLatestState],
                              resource: SourceResource[SecurityGroupIdentity]): Unit = {
    val groupName = resource.ref.identity.groupName
    val desiredState: SecurityGroupState = latestStateOption match {
      case None =>
        persist(SourceSecurityGroup(resource, "nonexistant"))(it => updateState(it))
        task.appName match {
          case Some(appName) if groupName == SecurityGroupConventions(appName).appSecurityGroupForElbName =>
            SecurityGroupConventions(appName).constructAppSecurityGroupForElb
          case _ =>
            SecurityGroupState(groupName, Set(), "")
        }
      case Some(latestState) =>
        persist(SourceSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
        latestState.state
    }
    val targetResource = resourceTracker.transformToTarget(resource)
    val referencingSource = resourceTracker.lookupReferencingSourceByTarget(targetResource)
    if (resourceTracker.isNonexistentTarget(targetResource)) {
      val newIpPermissions: Set[IpPermission] = constructIngressForNewSecurityGroup(groupName, desiredState)
      requireIngressSecurityGroups(newIpPermissions, targetResource.ref)
      sendTaskEvent(Mutation(taskId, Create(), CreateAwsResource(targetResource.ref, referencingSource)))
      if (task.dryRun) {
        self ! FoundTarget(targetResource)
      } else {
        val upsert: UpsertSecurityGroup = constructUpsertForNewSecurityGroup(resource,
          desiredState.copy(ipPermissions = newIpPermissions))
        logCreateEvent(targetResource, referencingSource)
        clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
      }
    }
  }

  private def constructUpsertForNewSecurityGroup(resource: SourceResource[SecurityGroupIdentity],
                                                 desiredState: SecurityGroupState): UpsertSecurityGroup = {
    val desiredStateSafeDescription = desiredState.copy(
      description = desiredState.description.replaceAll("[^A-Za-z0-9. _-]", "")
    )
    val securityGroupStateWithoutLegacySuffixes = desiredStateSafeDescription.
      removeLegacySuffixesFromSecurityGroupIngressRules()
    val vpcTransformation = new VpcTransformations().getVpcTransformation(task.source.vpcName, task.target.vpcName)
    vpcTransformation.log.toSet[String].foreach { msg =>
      sendTaskEvent(Log(taskId, msg))
    }
    val translatedIpPermissions = vpcTransformation.translateIpPermissions(resource.ref, securityGroupStateWithoutLegacySuffixes)
    val newSecurityGroupState = securityGroupStateWithoutLegacySuffixes.copy(ipPermissions = translatedIpPermissions)
    val upsert = UpsertSecurityGroup(newSecurityGroupState, overwrite = true)
    upsert
  }

  def constructIngressForNewSecurityGroup(groupName: String, securityGroupState: SecurityGroupState): Set[IpPermission] = {
    if (task.requiredSecurityGroupNames.contains(groupName)) {
      val sameAccountIpPermissions = filterOutCrossAccountIngress(groupName, securityGroupState)
      appendBoilerplateIngress(groupName, sameAccountIpPermissions)
    } else {
      Set()
    }
  }

  def appendBoilerplateIngress(groupName: String, ipPermissions: Set[IpPermission]): Set[IpPermission] = {
    val newIpPermissions: Set[IpPermission] = task.appName match {
      case Some(appName) if groupName == appName || groupName == SecurityGroupConventions(appName).appSecurityGroupForElbName =>
        val ipPermissionsWithClassicLink = if (task.allowIngressFromClassic) {
          ipPermissions + SecurityGroupConventions(appName).constructClassicLinkIpPermission
        } else {
          ipPermissions
        }
        if (groupName == appName) {
          ipPermissionsWithClassicLink + SecurityGroupConventions(appName).constructAppElbIpPermission
        } else {
          ipPermissionsWithClassicLink
        }
      case _ =>
        ipPermissions
    }
    newIpPermissions
  }

  private def filterOutCrossAccountIngress(groupName: String, securityGroupState: SecurityGroupState): Set[IpPermission] = {
    val sameAccountIpPermissions = securityGroupState.ipPermissions.map { ipPermission =>
      val sameAccountUserIdGroupPairs = ipPermission.userIdGroupPairs.filter { userIdGroupPair =>
        val isSameAccount = userIdGroupPair.userId == securityGroupState.ownerId
        if (!isSameAccount) {
          if (userIdGroupPair.userId != "amazon-elb") {
            val ingressGroupName = userIdGroupPair.groupName.get
            val msg = s"Cannot construct cross account security group ingress: (${securityGroupState.ownerId}.$groupName to ${userIdGroupPair.userId}.$ingressGroupName)"
            sendTaskEvent(Log(taskId, msg))
          }
        }
        isSameAccount
      }
      ipPermission.copy(userIdGroupPairs = sameAccountUserIdGroupPairs)
    }
    sameAccountIpPermissions.filter { ipPermission =>
      ipPermission.userIdGroupPairs.nonEmpty || ipPermission.ipRanges.nonEmpty
    }
  }

  private def requireIngressSecurityGroups(ipPermissions: Set[IpPermission], referencedBy: AwsReference[SecurityGroupIdentity]): Unit = {
    ipPermissions.foreach { ipPermission =>
      ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
        val groupName = referencedBy.identity.groupName
        val ingressGroupName = userIdGroupPair.groupName.get
        val referencedSourceSecurityGroup = resourceTracker.asSourceSecurityGroupReference(ingressGroupName)
        self ! RequiresSource(referencedSourceSecurityGroup, Option(referencedBy))
      }
    }
  }

  def logCreateEvent(targetResource: TargetResource[_ <: AwsIdentity], referencingSource: Option[AwsReference[_]]): Unit = {
    val reasonMsg = referencingSource match {
      case None => ""
      case Some(source) => s" because of reference from ${source.akkaIdentifier}."
    }
    sendTaskEvent(Log(taskId, s"Creating ${targetResource.ref.identity.akkaIdentifier}$reasonMsg."))
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
      case NonexistentTarget(target) =>
        resourceTracker.nonexistentTarget(target)
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
