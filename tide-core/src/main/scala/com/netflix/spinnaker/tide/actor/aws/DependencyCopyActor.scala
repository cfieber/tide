package com.netflix.spinnaker.tide.actor.aws

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.DependencyCopyActor._
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import akka.pattern.ask
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DependencyCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var checkForCreatedResources: Cancellable = _
//  override def postStop(): Unit = checkForCreatedResources.cancel()

  var orchestrator: ActorRef = _
  var awsResource: ActorRef = _
  var task: DependencyCopyTask = _
  var vpcIds: VpcIds = _
  var taskId: String = _

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  var securityGroupNameToSourceId: Map[String, String] = Map()
  var securityGroupNameToTargetId: Map[String, String] = Map()
  var resourcesRequired: Set[AwsIdentity] = Set()
  var resourcesFound: Set[AwsIdentity] = Set()
  var loadBalancerNameTargetToSource: Map[String, String] = Map()
  var cloudDriverReference: Option[ActorRef] = None
  var isComplete = false

  case class QualifiedVpcId(location: AwsLocation, vpcId: Option[String])

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ExecuteTask(_, newAwsResource, task: DependencyCopyTask, isContinued) if isContinued =>
      orchestrator = sender()
      awsResource = newAwsResource
      checkForCreatedResources = scheduler.schedule(0 seconds, 15 seconds, self, CheckCompletion())

    case event @ ExecuteTask(newTaskId, newAwsResource, task: DependencyCopyTask, isContinued) =>
      persist(event) { e =>
        updateState(e)
        orchestrator = sender()
        checkForCreatedResources = scheduler.schedule(15 seconds, 15 seconds, self, CheckCompletion())
        val future = (getShardCluster(VpcPollingActor.typeName) ? GetVpcs(task.target.location.account, task.target.location.region)).mapTo[List[Vpc]]
        val vpcs = Await.result(future, timeout.duration)
        val sourceVpc = vpcs.find(_.name == task.source.vpcName).map(_.vpcId)
        val targetVpc = vpcs.find(_.name == task.target.vpcName).map(_.vpcId)
        self ! VpcIds(sourceVpc, targetVpc)
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
          orchestrator ! it
          if (!task.dryRun) {
            val logMessage = event match {
              case taskSuccess: TaskSuccess => "All resources copied successfully."
              case taskFailure: TaskFailure => s"Failure: ${taskFailure.message}"
            }
            getShardCluster(TaskActor.typeName) ! Log(taskId, logMessage)
          }
        }
      }

    case event: CheckCompletion =>
      checkCompletion()

    case event: Requires =>
      if (!resourcesRequired.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
          getShardCluster(TaskActor.typeName) ! Log(taskId, s"Requires ${e.awsIdentity.akkaIdentifier}")
          checkForCreatedTargetResource(e.awsIdentity)
        }
      }

    case event: Found =>
      if (!resourcesFound.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
          if (!task.dryRun) {
            getShardCluster(TaskActor.typeName) ! Log(taskId, s"Found ${e.awsIdentity.akkaIdentifier}")
          }
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
              awsResource ! AwsResourceProtocol(AwsReference(task.source.location,
                SecurityGroupIdentity(name, vpcIds.source)), GetSecurityGroup(), None)
            case Some(latestState) =>
              persist(TargetSecurityGroup(name, latestState.securityGroupId))(it => updateState(it))
              self ! Found(SecurityGroupIdentity(name))
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
              val referenceToUpsert = AwsReference(task.target.location, SecurityGroupIdentity(name, vpcIds.target))
              getShardCluster(TaskActor.typeName) ! Create(taskId, referenceToUpsert)
              if (task.dryRun) {
                self ! Found(SecurityGroupIdentity(name))
              } else {
                val upsert = UpsertSecurityGroup(latestState.state, overwrite = false)
                awsResource ! AwsResourceProtocol(referenceToUpsert, upsert)
              }
          }
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
              val targetLoadBalancer = identity.forVpc(task.target.vpcName)
              val referenceToUpsert = AwsReference(task.target.location, targetLoadBalancer)
              getShardCluster(TaskActor.typeName) ! Create(taskId, referenceToUpsert)
              if (task.dryRun) {
                self ! Found(targetLoadBalancer)
              } else {
                val upsert = UpsertLoadBalancer(latestState.state.forVpc(task.target.vpcName, vpcIds.target), overwrite = false)
                awsResource ! AwsResourceProtocol(referenceToUpsert, upsert)
              }
          }
      }

  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, newAwsResource, copyTask: DependencyCopyTask, false) =>
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
            val targetIdentity = sourceIdentity.forVpc(task.target.vpcName)
            resourcesRequired += targetIdentity
            loadBalancerNameTargetToSource += (targetIdentity.loadBalancerName -> sourceIdentity.loadBalancerName)
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
    val missingDependencies = resourcesRequired.diff(resourcesFound)
    if (missingDependencies.isEmpty) {
      val securityGroupIdsSourceToTarget: Map[String, String] = if (task.dryRun) {
        Map()
      } else {
        securityGroupNameToSourceId.keySet.map { name =>
          name -> securityGroupNameToTargetId(name)
        }.toMap
      }
      self ! TaskSuccess(taskId, task, DependencyCopyTaskResult(securityGroupIdsSourceToTarget))
    } else {
      missingDependencies.foreach(checkForCreatedTargetResource)
    }
  }

  def checkForCreatedTargetResource(identity: AwsIdentity): Unit = {
    identity match {
      case identity: SecurityGroupIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(task.target.location, identity.copy(vpcId = vpcIds.target)),
          GetSecurityGroup(), None)
      case identity: LoadBalancerIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(task.target.location, identity.forVpc(task.target.vpcName)),
          GetLoadBalancer(), None)
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
        case msg: ExecuteTask =>
          (msg.akkaIdentifier, msg)
      },
      shardResolver = {
        case msg: ExecuteTask =>
          (msg.akkaIdentifier.hashCode % 10).toString
      })
  }
}
