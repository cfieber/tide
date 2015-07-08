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

package com.netflix.spinnaker.tide.actor.aws

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor._
import com.netflix.spinnaker.tide.actor.aws.DependencyCopyActor.{DependencyCopyTask, DependencyCopyTaskResult}
import com.netflix.spinnaker.tide.actor.aws.PipelineDeepCopyActor.{PipelineDeepCopyTaskResult, StartPipelineCloning, PipelineDeepCopyTask}
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector.{ChildTaskDescriptions, TaskDescription}
import com.netflix.spinnaker.tide.api._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

class PipelineDeepCopyActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  var awsResource: ActorRef = _
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
  var isComplete = false

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ContinueTask(executeTask) =>
      awsResource = executeTask.cloudResourceRef
      awsResource ! GetPipeline(task.sourceId)

    case event@ExecuteTask(newTaskId, newAwsResource, deepCopyTask: PipelineDeepCopyTask, isContinued) =>
      persist(event) { e =>
        updateState(e)
        sendTaskEvent(Log(taskId, s"Start deep copy of pipeline ${task.sourceId}"))
        awsResource ! GetPipeline(task.sourceId)
      }

    case event: TaskComplete =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          sendTaskEvent(it)
        }
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
              val mergedMappingForLocation = mappingForLocation ++ result.securityGroupIdSourceToTarget
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
          val dependencyCopyTasks: List[DependencyCopyTask] = clusterDependenciesForSourceVpc.map { dependencies =>
            val sourceVpcLocation = VpcLocation(AwsLocation(dependencies.account, dependencies.region), task.sourceVpcName)
            val targetVpcLocation = VpcLocation(AwsLocation(dependencies.account, dependencies.region), Option(task.targetVpcName))
            val securityGroupIdToName = getSecurityGroupInToNameMapping(dependencies.account, dependencies.region)
            val securityGroupNames = dependencies.securityGroupIds.map(securityGroupIdToName(_).groupName)
            DependencyCopyTask(sourceVpcLocation, targetVpcLocation, securityGroupNames, dependencies.loadBalancersNames,
              dryRun = task.dryRun)
          }
          startChildTasks(ChildTaskDescriptions(taskId, dependencyCopyTasks))
      }

    case event: StartPipelineCloning =>
      val pipelineWithDisabledTriggers = pipelineState.disableTriggers()
      val migrator = ClusterVpcMigrator(task.sourceVpcName, task.targetVpcName, event.securityGroupIdMappingByLocation)
      val migratedPipeline = pipelineWithDisabledTriggers.applyVisitor(migrator)
      val newPipeline = migratedPipeline.copy(name = s"${migratedPipeline.name} - ${task.targetVpcName}")
      sendTaskEvent(CreatePipeline(taskId, newPipeline))
      if (!task.dryRun) {
        sendTaskEvent(Log(taskId, s"Cloning pipeline '${pipelineState.name}'"))
        awsResource ! InsertPipeline(newPipeline)
      }
      self ! TaskSuccess(taskId, task, PipelineDeepCopyTaskResult("")) // TODO: get new pipeline ID

  }

  def getSecurityGroupInToNameMapping(account: String, region: String): Map[String, SecurityGroupIdentity] = {
    val future = getShardCluster(SecurityGroupPollingActor.typeName) ? GetSecurityGroupIdToNameMappings(account, region)
    val securityGroupsFuture = future.mapTo[Map[String, SecurityGroupIdentity]]
    val securityGroupIdToName: Map[String, SecurityGroupIdentity] = Await.result(securityGroupsFuture, timeout.duration)
    securityGroupIdToName
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, newAwsResource, deepCopyTask: PipelineDeepCopyTask, _) =>
        taskId = newTaskId
        awsResource = newAwsResource
        task = deepCopyTask
        isComplete = false
      case event: TaskComplete if event.description.isInstanceOf[PipelineDeepCopyTask] =>
        isComplete = true
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }
}

sealed trait PipelineDeepCopyProtocol extends Serializable

object PipelineDeepCopyActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class PipelineDeepCopyTaskResult(pipelineId: String) extends TaskResult with PipelineDeepCopyProtocol
  case class PipelineDeepCopyTask(sourceId: String, sourceVpcName: Option[String], targetVpcName: String, dryRun: Boolean = false)
    extends TaskDescription with PipelineDeepCopyProtocol {
    val taskType: String = "PipelineDeepCopyTask"
    override def executionActorTypeName: String = typeName
  }
  case class StartPipelineCloning(securityGroupIdMappingByLocation: Map[AwsLocation, Map[String, String]]) extends PipelineDeepCopyProtocol

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[PipelineDeepCopyActor]),
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

