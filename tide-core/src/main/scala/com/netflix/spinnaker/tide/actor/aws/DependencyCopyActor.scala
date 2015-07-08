package com.netflix.spinnaker.tide.actor.aws

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor._
import com.netflix.spinnaker.tide.actor.aws.DependencyCopyActor._
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import akka.pattern.ask
import com.netflix.spinnaker.tide.transform.VpcTransformations
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DependencyCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var checkForCreatedResources: Cancellable = _
//  override def postStop(): Unit = checkForCreatedResources.cancel()

  var awsResource: ActorRef = _
  var task: DependencyCopyTask = _
  var vpcIds: VpcIds = _
  var taskId: String = _

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    val taskCluster = ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
    taskCluster ! taskEvent
  }

  var securityGroupNameToSourceId: Map[String, String] = Map()
  var securityGroupNameToTargetId: Map[String, String] = Map()
  var resourcesRequired: Set[AwsIdentity] = Set()
  var resourcesFound: Set[AwsIdentity] = Set()
  var loadBalancerNameTargetToSource: Map[String, String] = Map()
  var securityGroupNameTargetToSource: Map[String, String] = Map()
  var cloudDriverReference: Option[ActorRef] = None
  var isComplete = false

  case class QualifiedVpcId(location: AwsLocation, vpcId: Option[String])

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ContinueTask(executeTask) =>
      awsResource = executeTask.cloudResourceRef
      checkForCreatedResources = scheduler.schedule(0 seconds, 15 seconds, self, CheckCompletion())

    case event @ ExecuteTask(_, _, task: DependencyCopyTask, _) =>
      persist(event) { e =>
        updateState(e)
        val future = (getShardCluster(VpcPollingActor.typeName) ? GetVpcs(task.target.location.account, task.target.location.region)).mapTo[List[Vpc]]
        val vpcs = Await.result(future, timeout.duration)
        val sourceVpc = vpcs.find(_.name == task.source.vpcName).map(_.vpcId)
        val targetVpc = vpcs.find(_.name == task.target.vpcName).map(_.vpcId)
        self ! VpcIds(sourceVpc, targetVpc)
        checkForCreatedResources = scheduler.schedule(15 seconds, 15 seconds, self, CheckCompletion())
        task.requiredSecurityGroupNames.foreach(it => self ! Requires(SecurityGroupIdentity(it)))
        task.sourceLoadBalancerNames.foreach(it => self ! Requires(LoadBalancerIdentity(it)))
      }

    case event: VpcIds =>
        persist(event) { it =>
          updateState(it)
        }

    case event: TaskComplete =>
      if (!isComplete) {
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
      }

    case event: CheckCompletion =>
      checkCompletion()

    case event: Requires =>
      val targetIdentity = transformToTargetIdentity(event.awsIdentity)
      if (!resourcesRequired.contains(targetIdentity)) {
        persist(event) { e =>
          updateState(e)
          sendTaskEvent(Log(taskId, s"Requires ${targetIdentity.akkaIdentifier}"))
          checkForCreatedTargetResource(e.awsIdentity)
          event.awsIdentity match {
            case sourceSecurityGroupIdentity: SecurityGroupIdentity =>
              awsResource ! AwsResourceProtocol(AwsReference(task.source.location,
                sourceSecurityGroupIdentity.copy(vpcId = vpcIds.source)), GetSecurityGroup(), None)
            case _ =>
          }
        }
      }

    case event: Found =>
      if (!resourcesFound.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
          sendTaskEvent(Log(taskId, s"Found ${e.awsIdentity.akkaIdentifier}"))
          checkCompletion()
        }
      }

    case event: SecurityGroupDetails =>
      val name = event.awsReference.identity.groupName
      val Source = QualifiedVpcId(task.source.location, vpcIds.source)
      val Target = QualifiedVpcId(task.target.location, vpcIds.target)
      QualifiedVpcId(event.awsReference.location, event.awsReference.identity.vpcId) match {
        case Target =>
          event.latestState match {
            case None =>
              val sourceSecurityGroupName = securityGroupNameTargetToSource(name)
              sendTaskEvent(CreateAwsResource(taskId, event.awsReference))
              awsResource ! AwsResourceProtocol(AwsReference(task.source.location,
                SecurityGroupIdentity(sourceSecurityGroupName, vpcIds.source)), GetSecurityGroup(), None)
            case Some(latestState) =>
              persist(TargetSecurityGroup(name, latestState.securityGroupId))(it => updateState(it))
              self ! Found(event.awsReference.identity)
          }
        case Source =>
          event.latestState match {
            case None => Nil
            case Some(latestState) =>
              persist(SourceSecurityGroup(name, latestState.securityGroupId))(it => updateState(it))
              latestState.state.ipPermissions.foreach { ipPermission =>
                ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
                  self ! Requires(SecurityGroupIdentity(userIdGroupPair.groupName.get))
                }
              }
              val targetSecurityGroupIdentity = transformToTargetIdentity(event.awsReference.identity)
              val referenceToUpsert = AwsReference(task.target.location, targetSecurityGroupIdentity)
              if (task.dryRun) {
                self ! Found(targetSecurityGroupIdentity)
              } else {
                val securityGroupStateWithoutLegacySuffixes = latestState.state.removeLegacySuffixesFromSecurityGroupIngressRules()
                val vpcTransformation = new VpcTransformations().getVpcTransformation(task.source.vpcName, task.target.vpcName)
                val translatedIpPermissions = vpcTransformation.translateIpPermissions(securityGroupStateWithoutLegacySuffixes.ipPermissions)
                val newSecurityGroupState = securityGroupStateWithoutLegacySuffixes.copy(ipPermissions = translatedIpPermissions)
                val upsert = UpsertSecurityGroup(newSecurityGroupState, overwrite = false)
                awsResource ! AwsResourceProtocol(referenceToUpsert, upsert)
              }
          }
        case other =>
          Nil
      }

    case event: LoadBalancerDetails =>
      val identity = event.awsReference.identity
      val name = identity.loadBalancerName
      event.latestState match {
        case None =>
          if (identity.isConsistentWithVpc(task.target.vpcName)) {
            val sourceLoadBalancerName = loadBalancerNameTargetToSource(name)
            val reference = AwsReference(task.source.location, LoadBalancerIdentity(sourceLoadBalancerName))
            awsResource ! AwsResourceProtocol(reference, GetLoadBalancer(), None)
          }
        case Some(latestState) =>
          val Source = QualifiedVpcId(task.source.location, vpcIds.source)
          val Target = QualifiedVpcId(task.target.location, vpcIds.target)
          QualifiedVpcId(event.awsReference.location, latestState.state.vpcId) match {
            case Target =>
              self ! Found(LoadBalancerIdentity(name))
            case Source =>
              latestState.state.securityGroups.foreach(it => self ! Requires(SecurityGroupIdentity(it)))
              val targetLoadBalancerIdentity = transformToTargetIdentity(identity)
              val referenceToUpsert = AwsReference(task.target.location, targetLoadBalancerIdentity)
              sendTaskEvent(CreateAwsResource(taskId, referenceToUpsert))
              if (task.dryRun) {
                self ! Found(targetLoadBalancerIdentity)
              } else {
                val newLoadBalancerState = latestState.state.forVpc(task.target.vpcName, vpcIds.target)
                  .removeLegacySuffixesFromSecurityGroups()
                val upsert = UpsertLoadBalancer(newLoadBalancerState, overwrite = false)
                awsResource ! AwsResourceProtocol(referenceToUpsert, upsert)
              }
          }
      }

  }

  def transformToTargetIdentity[T <: AwsIdentity](identity: T): T = {
    identity match {
      case sourceIdentity: LoadBalancerIdentity =>
        sourceIdentity.forVpc(task.target.vpcName).asInstanceOf[T]
      case sourceIdentity: SecurityGroupIdentity =>
        sourceIdentity.dropLegacySuffix.copy(vpcId = vpcIds.target).asInstanceOf[T]
    }
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, newAwsResource, copyTask: DependencyCopyTask, _) =>
        taskId = newTaskId
        awsResource = newAwsResource
        task = copyTask
        resourcesRequired = Set()
        resourcesFound = Set()
        isComplete = false
      case event: VpcIds =>
        vpcIds = event
      case event: TaskComplete =>
        isComplete = true
      case Requires(identity: AwsIdentity) =>
        identity match {
          case sourceIdentity: LoadBalancerIdentity =>
            val targetIdentity = transformToTargetIdentity(sourceIdentity)
            resourcesRequired += targetIdentity
            loadBalancerNameTargetToSource += (targetIdentity.loadBalancerName -> sourceIdentity.loadBalancerName)
          case sourceIdentity: SecurityGroupIdentity =>
            val targetIdentity = transformToTargetIdentity(sourceIdentity)
            resourcesRequired += targetIdentity
            securityGroupNameTargetToSource += (targetIdentity.groupName -> sourceIdentity.groupName)
          case _ =>
            resourcesRequired += identity
        }
      case Found(identity: AwsIdentity) =>
        resourcesFound += identity
      case event: SourceSecurityGroup =>
        securityGroupNameToSourceId += (event.name -> event.id)
      case event: TargetSecurityGroup =>
        securityGroupNameToTargetId += (event.name -> event.id)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }

  def checkCompletion() = {
    val identifiedTargetNames: Set[String] = securityGroupNameToTargetId.keySet
    val identifiedTargetNamesFromSource: Set[String] = securityGroupNameToSourceId.keySet.map { name =>
      transformToTargetIdentity(SecurityGroupIdentity(name)).groupName
    }
    val missingDependencies = resourcesRequired.diff(resourcesFound)
    if (missingDependencies.isEmpty && identifiedTargetNames == identifiedTargetNamesFromSource) {
      val securityGroupIdsSourceToTarget: Map[String, String] = securityGroupNameToTargetId.keySet.map { targetName =>
        val sourceName = securityGroupNameTargetToSource(targetName)
        securityGroupNameToSourceId(sourceName) -> securityGroupNameToTargetId(targetName)
      }.toMap
      self ! TaskSuccess(taskId, task, DependencyCopyTaskResult(securityGroupIdsSourceToTarget))
    } else {
      missingDependencies.foreach(checkForCreatedTargetResource)
      securityGroupNameTargetToSource.foreach {
        case (targetName, sourceName) =>
          awsResource ! AwsResourceProtocol(AwsReference(task.source.location, SecurityGroupIdentity(sourceName, vpcIds.source)), GetSecurityGroup(), None)
          awsResource ! AwsResourceProtocol(AwsReference(task.target.location, SecurityGroupIdentity(targetName, vpcIds.target)), GetSecurityGroup(), None)
      }
    }
  }

  def checkForCreatedTargetResource(identity: AwsIdentity): Unit = {
    val targetIdentity = transformToTargetIdentity(identity)
    targetIdentity match {
      case identity: SecurityGroupIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(task.target.location, identity), GetSecurityGroup(), None)
      case identity: LoadBalancerIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(task.target.location, identity), GetLoadBalancer(), None)
    }
  }

}

sealed trait DependencyCopyProtocol extends Serializable

object DependencyCopyActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class DependencyCopyTaskResult(securityGroupIdSourceToTarget: Map[String, String]) extends TaskResult with DependencyCopyProtocol
  case class DependencyCopyTask(source: VpcLocation,
                                target: VpcLocation,
                                requiredSecurityGroupNames: Set[String],
                                sourceLoadBalancerNames: Set[String], dryRun: Boolean = false) extends TaskDescription with DependencyCopyProtocol {
    val taskType: String = "DependencyCopyTask"
    val executionActorTypeName: String = typeName
  }
  case class VpcIds(source: Option[String], target: Option[String]) extends DependencyCopyProtocol
  case class CheckCompletion() extends DependencyCopyProtocol
  case class Requires(awsIdentity: AwsIdentity) extends DependencyCopyProtocol
  case class Found(awsIdentity: AwsIdentity) extends DependencyCopyProtocol

  case class SourceSecurityGroup(name: String, id: String) extends DependencyCopyProtocol
  case class TargetSecurityGroup(name: String, id: String) extends DependencyCopyProtocol

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[DependencyCopyActor]),
      idExtractor = {
        case msg: TaskProtocol =>
          (msg.taskId, msg)
      },
      shardResolver = {
        case msg: TaskProtocol =>
          (msg.taskId.hashCode % 10).toString
      })
  }
}
