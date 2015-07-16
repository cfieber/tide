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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi.VpcLocation
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.CloudDriverActor.{GetTaskDetail, CloudDriverResponse}
import com.netflix.spinnaker.tide.actor.aws.ServerGroupCloneActor.{StartServerGroupCloning, CloneServerGroupTask}
import com.netflix.spinnaker.tide.actor.aws.TaskActor.{TaskStatus, Create, Log}
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

class ServerGroupCloneActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  var awsResource: ActorRef = _
  var task: ServerGroupDeepCopyTask = _
  var taskId: String = _

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  var serverGroupState: Option[ServerGroupLatestState] = None
  var cloneServerGroupTaskId: Option[String] = None
  var isComplete = false

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ExecuteTask(_, newAwsResource, deepCopyTask: ServerGroupDeepCopyTask, isContinued) if isContinued =>
      awsResource = newAwsResource
      awsResource ! AwsResourceProtocol(task.source, GetServerGroup())

    case event @ ExecuteTask(newTaskId, newAwsResource, deepCopyTask: ServerGroupDeepCopyTask, isContinued) =>
      persist(event) { e =>
        updateState(e)
        getShardCluster(TaskActor.typeName) ! Log(taskId, s"Start deep copy of ${task.source.akkaIdentifier}")
        awsResource ! AwsResourceProtocol(task.source, GetServerGroup())
      }

    case event: TaskFailure =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          getShardCluster(TaskDirector.typeName) ! it
          getShardCluster(TaskActor.typeName) ! Log(taskId, s"Failure: ${event.message}")
        }
      }

    case event @ TaskSuccess(_, _, result: ServerGroupCloneTaskResult) =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          getShardCluster(TaskDirector.typeName) ! it
          getShardCluster(TaskActor.typeName) ! Log(taskId, s"Server Group deep copy succeeded. Created '${result.newServerGroupNames}'")
        }
      }

    case event @ TaskSuccess(_, _, result: DependencyCopyTaskResult) =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          self ! StartServerGroupCloning()
        }
      }

    case event: ServerGroupDetails =>
      event.latestState.foreach { latestState =>
        serverGroupState = Option(latestState)
        val requiredSecurityGroups = latestState.launchConfiguration.securityGroups
        val sourceLoadBalancerNames = latestState.autoScalingGroup.loadBalancerNames
        if (requiredSecurityGroups.isEmpty && sourceLoadBalancerNames.isEmpty) {
          self ! StartServerGroupCloning()
        } else {
          val sourceVpcLocation = VpcLocation(task.source.location, latestState.autoScalingGroup.vpcName)
          val dependencyCopyTask = DependencyCopyTask(sourceVpcLocation, task.target, requiredSecurityGroups,
            sourceLoadBalancerNames)
          getShardCluster(DependencyCopyActor.typeName) ! ExecuteTask(taskId, awsResource, dependencyCopyTask)
        }
      }

    case event: StartServerGroupCloning =>
      if (!cloneServerGroupTaskId.isDefined) {
        persist(event) { e =>
          updateState(e)
          val autoScalingGroup = serverGroupState.get.autoScalingGroup
          val newAutoScalingGroup = autoScalingGroup.forVpc(task.target.vpcName).withCapacity(0)
          val cloneServerGroup = AwsResourceProtocol(task.source,
            CloneServerGroup(newAutoScalingGroup, serverGroupState.get.launchConfiguration, startDisabled = true))
          val future = (awsResource ? cloneServerGroup).mapTo[CloudDriverResponse]
          getShardCluster(TaskActor.typeName) ! Log(taskId, s"Cloning Server Group ${task.source.akkaIdentifier}")
          val cloudDriverResponse = Await.result(future, timeout.duration)
          cloneServerGroupTaskId = Option(cloudDriverResponse.taskDetail.id)
          self ! CloneServerGroupTask(cloudDriverResponse)
          scheduler.scheduleOnce(15 seconds, cloudDriverResponse.cloudDriverReference,
            GetTaskDetail(cloudDriverResponse.taskDetail.id))
        }
      }

    case event: CloneServerGroupTask =>
      persist(event) { e =>
        updateState(e)
      }

    case event: CloudDriverResponse =>
      updateState(event)
      cloneServerGroupTaskId.foreach { id =>
        if (event.taskDetail.id == id) {
          val taskDetail = event.taskDetail
          if (taskDetail.status.completed) {
            if (taskDetail.status.failed) {
              self ! TaskFailure(taskId, task, taskDetail.status.status)
            } else {
              self ! TaskSuccess(taskId, task, ServerGroupCloneTaskResult(taskDetail.getCreatedServerGroups))
            }
          } else {
            scheduler.scheduleOnce(15 seconds, event.cloudDriverReference, GetTaskDetail(event.taskDetail.id))
          }
        }
      }
  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, newAwsResource, deepCopyTask: ServerGroupDeepCopyTask, false) =>
        taskId = newTaskId
        awsResource = newAwsResource
        task = deepCopyTask
        cloneServerGroupTaskId = None
        isComplete = false
      case event: TaskComplete =>
        isComplete = true
      case event: CloneServerGroupTask =>
        cloneServerGroupTaskId = Option(event.response.taskDetail.id)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }
}

sealed trait ServerGroupCloneProtocol extends Serializable

object ServerGroupCloneActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class GetTargetVpcId() extends ServerGroupCloneProtocol
  case class StartServerGroupCloning() extends ServerGroupCloneProtocol
  case class CloneServerGroupTask(response: CloudDriverResponse) extends ServerGroupCloneProtocol

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[ServerGroupCloneActor]),
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
