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
import com.netflix.frigga.autoscaling.AutoScalingGroupNameBuilder
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{AutoScalingGroupIdentity, AwsReference, VpcLocation}
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.CloudDriverActor.{GetTaskDetail, CloudDriverResponse}
import com.netflix.spinnaker.tide.actor.aws.DependencyCopyActor.{DependencyCopyTask, DependencyCopyTaskResult}
import com.netflix.spinnaker.tide.actor.aws.ServerGroupCloneActor.{ServerGroupCloneTaskResult, ServerGroupDeepCopyTask, CloudDriverTaskReference, StartServerGroupCloning}
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector.typeName
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
  var cloneServerGroupTaskReference: Option[CloudDriverTaskReference] = None

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  var serverGroupState: Option[ServerGroupLatestState] = None
  var isComplete = false

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {

    case ExecuteTask(_, newAwsResource, deepCopyTask: ServerGroupDeepCopyTask, isContinued) if isContinued =>
      awsResource = newAwsResource
      awsResource ! AwsResourceProtocol(deepCopyTask.source, GetServerGroup())

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
        }
      }

    case event @ TaskSuccess(_, _, result: ServerGroupCloneTaskResult) =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          getShardCluster(TaskDirector.typeName) ! it
        }
      }

    case event @ TaskSuccess(_, _, result: DependencyCopyTaskResult) =>
      persist(event) { it =>
        updateState(it)
        self ! StartServerGroupCloning()
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
            sourceLoadBalancerNames, dryRun = task.dryRun)
          getShardCluster(DependencyCopyActor.typeName) ! ExecuteTask(taskId, awsResource, dependencyCopyTask)
        }
      }

    case event: StartServerGroupCloning =>
      if (!cloneServerGroupTaskReference.isDefined) {
        val newAutoScalingGroup = serverGroupState.get.autoScalingGroup.forVpc(task.target.vpcName).withCapacity(0)
        val newLaunchConfiguration = serverGroupState.get.launchConfiguration.dropSecurityGroupNameLegacySuffixes
        val cloneServerGroup = AwsResourceProtocol(task.source,
          CloneServerGroup(newAutoScalingGroup, newLaunchConfiguration, startDisabled = true))
        if (task.dryRun) {
          val nextAsgIdentity = task.source.identity.nextGroup
          getShardCluster(TaskActor.typeName) ! Create(taskId, AwsReference(task.target.location, nextAsgIdentity))
          self ! TaskSuccess(taskId, task, ServerGroupCloneTaskResult(Seq(nextAsgIdentity.autoScalingGroupName)))
        } else {
          getShardCluster(TaskActor.typeName) ! Log(taskId, s"Cloning Server Group ${task.source.akkaIdentifier}")
          val future = (awsResource ? cloneServerGroup).mapTo[CloudDriverResponse]
          val cloudDriverResponse = Await.result(future, timeout.duration)
          persist(CloudDriverTaskReference(cloudDriverResponse.taskDetail.id, cloudDriverResponse.cloudDriverReference)) { it =>
            updateState(it)
            cloneServerGroupTaskReference.foreach { taskReference =>
              scheduler.scheduleOnce(15 seconds, taskReference.actorRef, GetTaskDetail(taskReference.taskId))
            }
          }
        }
      } else {
        cloneServerGroupTaskReference.foreach { taskReference =>
          scheduler.scheduleOnce(15 seconds, taskReference.actorRef, GetTaskDetail(taskReference.taskId))
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
              self ! TaskSuccess(taskId, task, ServerGroupCloneTaskResult(taskDetail.getCreatedServerGroups))
              taskDetail.getCreatedServerGroups.foreach { groupName =>
                val reference = AwsReference(task.target.location, AutoScalingGroupIdentity(groupName))
                getShardCluster(TaskActor.typeName) ! Create(taskId, reference)
              }
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

sealed trait ServerGroupCloneProtocol extends Serializable

object ServerGroupCloneActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class ServerGroupCloneTaskResult(newServerGroupNames: Seq[String]) extends TaskResult with ServerGroupCloneProtocol
  case class ServerGroupDeepCopyTask(source: AwsReference[AutoScalingGroupIdentity], target: VpcLocation,
                                     dryRun: Boolean = false)
    extends TaskDescription with ServerGroupCloneProtocol {
    val taskType: String = "ServerGroupDeepCopyTask"
  }
  case class CloudDriverTaskReference(taskId: String, actorRef: ActorRef) extends ServerGroupCloneProtocol
  case class StartServerGroupCloning() extends ServerGroupCloneProtocol

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
