/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.tide.actor.copy

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryFailure, PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.PipelineActor.{PipelineDetails, GetPipeline}
import com.netflix.spinnaker.tide.actor.service.Front50Actor.SavePipeline
import com.netflix.spinnaker.tide.actor.{ClusteredActorObject, TaskActorObject}
import com.netflix.spinnaker.tide.actor.aws.PipelineActor
import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor.{DependencyCopyTaskResult, DependencyCopyTask}
import com.netflix.spinnaker.tide.actor.copy.PipelineDeepCopyActor.{CreatePipeline, StartPipelineCloning, PipelineDeepCopyTaskResult, PipelineDeepCopyTask}
import com.netflix.spinnaker.tide.actor.polling.{SecurityGroupPollingContract, SecurityGroupPollingContractActor}
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.service.Front50Actor
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector.{ChildTaskDescriptions, TaskDescription}
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskDirector, TaskProtocol}
import com.netflix.spinnaker.tide.model.Front50Service.{PipelineState, ClusterVisitor, Cluster}
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.concurrent.duration.DurationInt

class PipelineDeepCopyActor extends PersistentActor with ActorLogging {

  val clusterSharding = ClusterSharding.get(context.system)

  val securityGroupPolling: SecurityGroupPollingContract = new SecurityGroupPollingContractActor(clusterSharding)

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  var task: PipelineDeepCopyTask = _
  var taskId: String = _

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    getShardCluster(TaskActor.typeName) ! taskEvent
  }

  def startChildTasks(childTaskDescriptions: ChildTaskDescriptions) = {
    getShardCluster(TaskDirector.typeName) ! childTaskDescriptions
  }

  var pipelineState: PipelineState = _

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ContinueTask(ExecuteTask(newTaskId, deepCopyTask: PipelineDeepCopyTask, _)) =>
      clusterSharding.shardRegion(PipelineActor.typeName) ! GetPipeline(task.sourceId)

    case event @ ExecuteTask(newTaskId, _: PipelineDeepCopyTask, _) =>
      persist(event) { e =>
        updateState(e)
        sendTaskEvent(Log(taskId, s"Start deep copy of pipeline ${task.sourceId}"))
        clusterSharding.shardRegion(PipelineActor.typeName) ! GetPipeline(task.sourceId)
      }

    case event: TaskComplete =>
      persist(event) { it =>
        updateState(it)
        sendTaskEvent(it)
      }

    case event: ChildTaskGroupComplete =>
      persist(event) { it =>
        updateState(it)
        val firstFailure = event.taskCompletes.find { taskComplete =>
          !taskComplete.isInstanceOf[TaskSuccess]
        }
        firstFailure match {
          case Some(failure: TaskFailure) => throw new IllegalStateException(s"Failed child task '${failure.taskId}' - '${failure.message}'.")
          case None =>
            var securityGroupIdMappingByLocation: Map[AwsLocation, Map[String, String]] = Map()
            event.taskCompletes.foreach { taskComplete =>
              val taskSuccess = taskComplete.asInstanceOf[TaskSuccess]
              val description = taskSuccess.description.asInstanceOf[DependencyCopyTask]
              val result = taskSuccess.result.asInstanceOf[DependencyCopyTaskResult]
              val location = description.source.location
              val mappingForLocation: Map[String, String] = securityGroupIdMappingByLocation.getOrElse(location, Map())
              val mergedMappingForLocation = mappingForLocation ++ result.securityGroupIdSourceToTarget ++ result.targetSecurityGroupNameToId
              securityGroupIdMappingByLocation += (location -> mergedMappingForLocation)
            }
            self ! StartPipelineCloning(securityGroupIdMappingByLocation)
        }
      }

    case event: PipelineDetails =>
      event.state match {
        case None =>
          throw new IllegalArgumentException(s"Cannot find pipeline '${event.id}'.")
        case Some(latestState) =>
          pipelineState = latestState
          val collector = ClusterDependencyCollector()
          latestState.applyVisitor(collector)
          val clusterDependenciesForSourceVpc = collector.dependencies.filter { clusterDependencies =>
            val vpcName: Option[String] = AwsApi.getVpcNameFromSubnetType(clusterDependencies.subnetType)
            vpcName == task.sourceVpcName
          }
          val dependencyCopyTasks: Seq[DependencyCopyTask] = clusterDependenciesForSourceVpc.map { dependencies =>
            val sourceVpcLocation = VpcLocation(AwsLocation(dependencies.account, dependencies.region), task.sourceVpcName)
            val targetVpcLocation = VpcLocation(AwsLocation(dependencies.account, dependencies.region), Option(task.targetVpcName))
            val securityGroupIdToName = getSecurityGroupInToNameMapping(dependencies.account, dependencies.region)
            val securityGroupNames = dependencies.securityGroupIds.map(securityGroupIdToName(_).groupName)
            DependencyCopyTask(sourceVpcLocation, targetVpcLocation, securityGroupNames,
              dependencies.loadBalancersNames, Option(dependencies.appName),
              allowIngressFromClassic = task.allowIngressFromClassic, dryRun = task.dryRun)
          }
          startChildTasks(ChildTaskDescriptions(taskId, dependencyCopyTasks))
      }

    case event: StartPipelineCloning =>
      val pipelineWithDisabledTriggers = pipelineState.disableTriggers()
      val migrator = ClusterVpcMigrator(task.sourceVpcName, task.targetVpcName, task.targetSubnetType, event.securityGroupIdMappingByLocation)
      val migratedPipeline = pipelineWithDisabledTriggers.applyVisitor(migrator)
      val newPipeline = migratedPipeline.copy(name = s"${migratedPipeline.name} - ${task.targetVpcName}")
      sendTaskEvent(Mutation(taskId, Create(), CreatePipeline(newPipeline)))
      if (!task.dryRun) {
        sendTaskEvent(Log(taskId, s"Cloning pipeline '${pipelineState.name}'"))
        clusterSharding.shardRegion(Front50Actor.typeName) ! SavePipeline(newPipeline)
      }
      self ! TaskSuccess(taskId, task, PipelineDeepCopyTaskResult("")) // TODO: get new pipeline ID

  }

  def getSecurityGroupInToNameMapping(account: String, region: String): Map[String, SecurityGroupIdentity] = {
    securityGroupPolling.ask(GetSecurityGroupIdToNameMappings(AwsLocation(account, region))).map
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, deepCopyTask: PipelineDeepCopyTask, _) =>
        taskId = newTaskId
        task = deepCopyTask
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case msg: RecoveryFailure => log.error(msg.cause, msg.cause.toString)
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }
}

sealed trait PipelineDeepCopyProtocol extends Serializable

object PipelineDeepCopyActor extends ClusteredActorObject with TaskActorObject {
  val props = Props[PipelineDeepCopyActor]

  case class CreatePipeline(pipelineToCreate: PipelineState) extends MutationDetails with PipelineDeepCopyProtocol
  case class PipelineDeepCopyTaskResult(pipelineId: String) extends TaskResult with PipelineDeepCopyProtocol
  case class PipelineDeepCopyTask(sourceId: String,
                                  sourceVpcName: Option[String],
                                  targetVpcName: String,
                                  allowIngressFromClassic: Boolean = true,
                                  targetSubnetType: Option[String],
                                  dryRun: Boolean = false)
    extends TaskDescription with PipelineDeepCopyProtocol {
    val taskType: String = "PipelineDeepCopyTask"
    override def executionActorTypeName: String = typeName
    override def summary: String = s"Copy pipeline $sourceId from $sourceVpcName to $targetVpcName (dryRun: $dryRun)."
  }
  case class StartPipelineCloning(securityGroupIdMappingByLocation: Map[AwsLocation, Map[String, String]]) extends PipelineDeepCopyProtocol

}

case class ClusterVpcMigrator(sourceVpcName: Option[String], targetVpcName: String, targetSubnetType: Option[String],
                              securityGroupIdMappingByLocation: Map[AwsLocation, Map[String, String]]) extends ClusterVisitor {
  def visit(cluster: Cluster): Cluster = {
    val location = AwsLocation(cluster.getAccount, cluster.getRegion)
    val subnetType = cluster.getSubnetType
    val vpcName = AwsApi.getVpcNameFromSubnetType(subnetType)
    if (vpcName == sourceVpcName) {
      val newSubnetType = AwsApi.constructTargetSubnetType(targetSubnetType.getOrElse(subnetType.getOrElse("internal")), Option(targetVpcName))
      val newLoadBalancers = cluster.getLoadBalancersNames.map(LoadBalancerIdentity(_)
        .forVpc(sourceVpcName, Option(targetVpcName)).loadBalancerName)
      val newSecurityGroups = securityGroupIdMappingByLocation.get(location) match {
        case Some(securityGroupIdsSourceToTarget) =>
          val targetIds = cluster.getSecurityGroupIds.map(securityGroupIdsSourceToTarget.getOrElse(_, "nonexistant"))
          securityGroupIdsSourceToTarget.get(cluster.getApplication) match {
            case None => targetIds
            case Some(id) => targetIds + id
          }
        case None =>
          cluster.getSecurityGroupIds
      }
      cluster.setSubnetType (newSubnetType).setLoadBalancersNames(newLoadBalancers)
        .setSecurityGroupIds(newSecurityGroups)
    } else {
      cluster
    }
  }
}

case class ClusterDependencyCollector() extends ClusterVisitor {
  var dependencies: List[ClusterDependencies] = Nil

  def visit(cluster: Cluster): Cluster = {
    val clusterDependencies = ClusterDependencies(cluster.getAccount, cluster.getRegion, cluster.getSubnetType,
      cluster.getLoadBalancersNames, cluster.getSecurityGroupIds, cluster.getApplication)
    dependencies ::= clusterDependencies
    cluster
  }
}

case class ClusterDependencies(account: String, region: String, subnetType: Option[String],
                               loadBalancersNames: Set[String], securityGroupIds: Set[String], appName: String)


