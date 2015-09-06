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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.TaskActorObject
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor
import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor.DependencyCopyTask
import com.netflix.spinnaker.tide.actor.copy.ServerGroupDeepCopyActor.{ServerGroupDeepCopyTaskResult, StartServerGroupCloning, CloudDriverTaskReference, ServerGroupDeepCopyTask}
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor.{CloudDriverResponse, GetTaskDetail}
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector.{ChildTaskDescriptions, TaskDescription}
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskDirector, TaskProtocol}
import com.netflix.spinnaker.tide.model.AwsApi.{CreateAwsResource, AutoScalingGroupIdentity, VpcLocation, AwsReference}
import com.netflix.spinnaker.tide.model._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ServerGroupDeepCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  val clusterSharding = ClusterSharding.get(context.system)

  var task: ServerGroupDeepCopyTask = _
  var taskId: String = _
  var cloneServerGroupTaskReference: Option[CloudDriverTaskReference] = None

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    val taskCluster = ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
    taskCluster ! taskEvent
  }

  def startChildTasks(childTaskDescriptions: ChildTaskDescriptions) = {
    ClusterSharding.get(context.system).shardRegion(TaskDirector.typeName) ! childTaskDescriptions
  }

  var serverGroupState: ServerGroupLatestState = _
  var isComplete = false

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ContinueTask(ExecuteTask(_, deepCopyTask: ServerGroupDeepCopyTask, _)) =>
      clusterSharding.shardRegion(ServerGroupActor.typeName) ! AwsResourceProtocol(task.source, GetServerGroup())

    case event @ ExecuteTask(_, _: ServerGroupDeepCopyTask, _) =>
      persist(event) { e =>
        updateState(e)
        sendTaskEvent(Log(taskId, s"Start deep copy of ${task.source.akkaIdentifier}"))
        clusterSharding.shardRegion(ServerGroupActor.typeName) ! AwsResourceProtocol(task.source, GetServerGroup())
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
          taskComplete.isInstanceOf[TaskFailure]
        }
        firstFailure match {
          case Some(failure: TaskFailure) => throw new IllegalStateException(s"Failed child task '${failure.taskId}' - '${failure.message}'.")
          case None => self ! StartServerGroupCloning()
        }
      }

    case event: ServerGroupDetails =>
      event.latestState match {
        case None =>
          throw new IllegalArgumentException(s"Cannot find server group '${event.awsReference}'.")
        case Some(latestState) =>
          serverGroupState = latestState
          val requiredSecurityGroups = latestState.launchConfiguration.securityGroups
          val sourceLoadBalancerNames = latestState.autoScalingGroup.loadBalancerNames
          if (requiredSecurityGroups.isEmpty && sourceLoadBalancerNames.isEmpty) {
            self ! StartServerGroupCloning()
          } else {
            val sourceVpcLocation = VpcLocation(task.source.location, latestState.autoScalingGroup.vpcName)
            val dependencyCopyTask = DependencyCopyTask(sourceVpcLocation, task.target,
              requiredSecurityGroups, sourceLoadBalancerNames, dryRun = task.dryRun)
            startChildTasks(ChildTaskDescriptions(taskId, List(dependencyCopyTask)))
          }
      }

    case event: StartServerGroupCloning =>
      val cloudDriver = clusterSharding.shardRegion(CloudDriverActor.typeName)
      if (cloneServerGroupTaskReference.isEmpty) {
        val newAutoScalingGroup = serverGroupState.autoScalingGroup.forVpc(task.target.vpcName).withCapacity(0)
        val newLaunchConfiguration = serverGroupState.launchConfiguration.dropSecurityGroupNameLegacySuffixes
        val cloneServerGroup = CloneServerGroup(newAutoScalingGroup, newLaunchConfiguration, startDisabled = true)
        if (task.dryRun) {
          val nextAsgIdentity = task.source.identity.nextGroup
          sendTaskEvent(CreateAwsResource(taskId, AwsReference(task.target.location, nextAsgIdentity), None, Option(cloneServerGroup)))
          self ! TaskSuccess(taskId, task, ServerGroupDeepCopyTaskResult(Seq(nextAsgIdentity.autoScalingGroupName)))
        } else {
          sendTaskEvent(Log(taskId, s"Cloning server group ${task.source.akkaIdentifier}"))
          val resourceProtocol = AwsResourceProtocol(task.source, cloneServerGroup)
          val future = (cloudDriver ? resourceProtocol).mapTo[CloudDriverResponse]
          val cloudDriverResponse = Await.result(future, timeout.duration)
          persist(CloudDriverTaskReference(cloudDriverResponse.taskDetail.id)) { it =>
            updateState(it)
            cloneServerGroupTaskReference.foreach { taskReference =>
              scheduler.scheduleOnce(15 seconds, cloudDriver, GetTaskDetail(taskReference.taskId))
            }
          }
        }
      } else {
        cloneServerGroupTaskReference.foreach { taskReference =>
          scheduler.scheduleOnce(15 seconds, cloudDriver, GetTaskDetail(taskReference.taskId))
        }
      }

    case event: CloudDriverResponse =>
      updateState(event)
      cloneServerGroupTaskReference.foreach { taskReference =>
        if (event.taskDetail.id == taskReference.taskId) {
          val taskDetail = event.taskDetail
          if (taskDetail.status.completed) {
            if (taskDetail.status.failed) {
              self ! TaskFailure(taskId, task, taskDetail.status.status)
            } else {
              self ! TaskSuccess(taskId, task, ServerGroupDeepCopyTaskResult(taskDetail.getCreatedServerGroups))
              taskDetail.getCreatedServerGroups.foreach { groupName =>
                val reference = AwsReference(task.target.location, AutoScalingGroupIdentity(groupName))
                sendTaskEvent(CreateAwsResource(taskId, reference, None))
              }
            }
          } else {
            val cloudDriver = clusterSharding.shardRegion(CloudDriverActor.typeName)
            scheduler.scheduleOnce(15 seconds, cloudDriver, GetTaskDetail(event.taskDetail.id))
          }
        }
      }
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, deepCopyTask: ServerGroupDeepCopyTask, _) =>
        taskId = newTaskId
        task = deepCopyTask
        cloneServerGroupTaskReference = None
        isComplete = false
      case event: TaskComplete if event.description.isInstanceOf[ServerGroupDeepCopyTask] =>
        isComplete = true
      case event: CloudDriverTaskReference =>
        cloneServerGroupTaskReference = Option(event)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }
}

sealed trait ServerGroupDeepCopyProtocol extends Serializable

object ServerGroupDeepCopyActor extends TaskActorObject {
  val props = Props[ServerGroupDeepCopyActor]

  case class ServerGroupDeepCopyTaskResult(newServerGroupNames: Seq[String]) extends TaskResult with ServerGroupDeepCopyProtocol
  case class ServerGroupDeepCopyTask(source: AwsReference[AutoScalingGroupIdentity],
                                     target: VpcLocation,
                                     dryRun: Boolean = false)
    extends TaskDescription with ServerGroupDeepCopyProtocol {
    val taskType: String = "ServerGroupDeepCopyTask"
    val executionActorTypeName: String = typeName
  }
  case class CloudDriverTaskReference(taskId: String) extends ServerGroupDeepCopyProtocol
  case class StartServerGroupCloning() extends ServerGroupDeepCopyProtocol

}
