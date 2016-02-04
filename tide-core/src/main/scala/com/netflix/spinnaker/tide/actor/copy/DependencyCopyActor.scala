package com.netflix.spinnaker.tide.actor.copy

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{PersistentActor, RecoveryCompleted}
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
import com.netflix.spinnaker.tide.transform.VpcTransformations
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
      checkForCreatedResources = scheduler.schedule(0 seconds, 25 seconds, self, CheckCompletion())

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
        checkForCreatedResources = scheduler.schedule(25 seconds, 25 seconds, self, CheckCompletion())
        task.requiredSecurityGroupNames.foreach { it =>
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

    case event: SecurityGroupDetails if !complete =>
      resourceTracker.asResource(event.awsReference) match {
        case resource: TargetResource[SecurityGroupIdentity] =>
          event.latestState match {
            case None =>
              if (task.dryRun) {
                persist(TargetSecurityGroup(resource, "nonexistant"))(it => updateState(it))
              }
              self ! NonexistentTarget(resource)
            case Some(latestState) =>
              persist(TargetSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
              self ! FoundTarget(resource)
          }
          val sourceResource = resourceTracker.getSourceByTarget(resource)
          clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(sourceResource.ref, GetSecurityGroup())
        case resource: SourceResource[SecurityGroupIdentity] =>
          event.latestState match {
            case None => Nil
            case Some(latestState) =>
              persist(SourceSecurityGroup(resource, latestState.securityGroupId))(it => updateState(it))
              val targetResource = resourceTracker.transformToTarget(resource)
              val referencingSource = resourceTracker.lookupReferencingSourceByTarget(targetResource)
              if (resourceTracker.isNonexistentTarget(targetResource)) {
                if (task.source.vpcName.isDefined) {
                  requireIngressSecurityGroups(latestState, targetResource.ref)
                }
                sendTaskEvent(Mutation(taskId, Create(), CreateAwsResource(targetResource.ref, referencingSource)))
              }
              if (task.dryRun) {
                self ! FoundTarget(targetResource)
              } else {
                if (resourceTracker.isNonexistentTarget(targetResource) && !resourceTracker.hasSecuritySourceGroupIdFor(resource)) {
                  val securityGroupStateWithoutLegacySuffixes = latestState.state.removeLegacySuffixesFromSecurityGroupIngressRules()
                  val translatedIpPermissions: Set[IpPermission] = if (task.source.vpcName.isEmpty) {
                    Set()
                  } else {
                    val vpcTransformation = new VpcTransformations().getVpcTransformation(task.source.vpcName, task.target.vpcName)
                    vpcTransformation.log.toSet[String].foreach { msg =>
                      sendTaskEvent(Log(taskId, msg))
                    }
                    vpcTransformation.translateIpPermissions(resource.ref, securityGroupStateWithoutLegacySuffixes)
                  }
                  val newSecurityGroupState = securityGroupStateWithoutLegacySuffixes.copy(ipPermissions = translatedIpPermissions)
                  val upsert = UpsertSecurityGroup(newSecurityGroupState, overwrite = false)
                  logCreateEvent(targetResource, referencingSource)
                  clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
                }
              }
          }
        case other => Nil
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
              }
              if (task.dryRun) {
                self ! FoundTarget(targetResource)
              } else {
                if (resourceTracker.isNonexistentTarget(targetResource)) {
                  val newLoadBalancerState = latestState.state.forVpc(task.target.vpcName, vpcIds.target)
                    .removeLegacySuffixesFromSecurityGroups()
                  val upsert = UpsertLoadBalancer(newLoadBalancerState, overwrite = false)
                  logCreateEvent(targetResource, referencingSource)
                  clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(targetResource.ref, upsert)
                }
              }
          }
        case other => Nil
      }

  }

  private def requireIngressSecurityGroups(latestState: SecurityGroupLatestState, referencedBy: AwsReference[SecurityGroupIdentity]): Unit = {
    latestState.state.ipPermissions.foreach { ipPermission =>
      ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
        val groupName = referencedBy.identity.groupName
        val ingressGroupName = userIdGroupPair.groupName.get
        if (userIdGroupPair.userId != "amazon-elb") {
          if (userIdGroupPair.userId != latestState.state.ownerId) {
            val msg = s"Cannot construct cross account security group ingress: (${latestState.state.ownerId}.$groupName to ${userIdGroupPair.userId}.$ingressGroupName)"
            if (task.dryRun) {
              sendTaskEvent(Log(taskId, msg))
            } else {
              throw new IllegalStateException(msg)
            }
          } else {
            val referencedSourceSecurityGroup = resourceTracker.asSourceSecurityGroupReference(ingressGroupName)
            self ! RequiresSource(referencedSourceSecurityGroup, Option(referencedBy))
          }
        }
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
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }

  def checkCompletion() = {
    if (resourceTracker.hasResolvedEverythingRequired) {
      self ! TaskSuccess(taskId, task, DependencyCopyTaskResult(resourceTracker.securityGroupIdsSourceToTarget))
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

  case class DependencyCopyTaskResult(securityGroupIdSourceToTarget: Map[String, String]) extends TaskResult with DependencyCopyProtocol
  case class DependencyCopyTask(source: VpcLocation,
                                target: VpcLocation,
                                requiredSecurityGroupNames: Set[String],
                                sourceLoadBalancerNames: Set[String],
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
