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
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.AwsResourceReference
import com.netflix.spinnaker.tide.actor.aws.TaskActor.GetTask
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class TaskDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  var awsResource: AwsResourceActor.Ref = _

  var currentTasksById: Map[String, TaskDescription] = Map()

  var nextTaskId: Long = 1

  def deepCopyCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(DeepCopyActor.typeName)
  }

  def taskCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
  }

  override def receiveCommand: Receive = {
    case event: AwsResourceReference =>
      awsResource = event.awsResource
      context become directTasks
      currentTasksById.foreach {
        case (id, task) => routeTask(task, isContinued = true, id)
      }
    case event => Nil
  }

  def directTasks: Receive = {
    case event: AwsResourceReference =>
      awsResource = event.awsResource

    case event: GetRunningTasks =>
      sender() ! currentTasksById.keySet

    case event: GetTask =>
      taskCluster forward event

    case event: TaskDescription =>
      persist(event) { it =>
        updateState(it)
        routeTask(it)
      }

    case event: TaskComplete =>
      persist(event) { it =>
        updateState(it)
      }
  }

  override def receiveRecover: Receive = {
    case event: RecoveryCompleted => Nil
    case event =>
      updateState(event)
  }

  def routeTask(taskDescription: TaskDescription, isContinued: Boolean = false, id: String = nextTaskId.toString): Unit = {
    taskDescription match {
      case event : DeepCopyTask =>
        deepCopyCluster ! ExecuteTask(id, awsResource, taskDescription, isContinued)
    }
  }

  def updateState(event: Any) = {
    event match {
      case event: ExecuteTask =>
        nextTaskId = nextTaskId + 1
        currentTasksById += (event.taskId -> event.description)
      case event: TaskComplete =>
        currentTasksById -= event.taskId
    }
  }
}

sealed trait TaskDirectorProtocol

object TaskDirector {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  sealed trait TaskComplete extends TaskDirectorProtocol {
    def taskId: String
  }
  case class TaskSuccess(taskId: String, newServerGroupName: String) extends TaskComplete
  case class TaskFailure(taskId: String, message: String) extends TaskComplete

  case class GetRunningTasks() extends TaskDirectorProtocol

  case class ExecuteTask(taskId: String, awsResource: ActorRef, description: TaskDescription, isContinued: Boolean = false)
    extends TaskDirectorProtocol with AkkaClustered {
    val akkaIdentifier: String = description.akkaIdentifier
  }

  sealed trait TaskDescription extends AkkaClustered
  case class DeepCopyTask(source: AwsReference[AutoScalingGroupIdentity], target: Target)
    extends TaskDescription {
    val akkaIdentifier: String = s"DeepCopy.${source.akkaIdentifier}.${target.akkaIdentifier}"
  }

  case class Target(@BeanProperty account: String, @BeanProperty region: String, @BeanProperty vpcName: String)
    extends TaskDirectorProtocol with AkkaClustered {
    @JsonIgnore def location: AwsLocation = {
      AwsLocation(account, region)
    }
    val akkaIdentifier: String = s"${location.akkaIdentifier}.$vpcName"
  }

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[TaskDirector]),
      idExtractor = {
        case msg => ("singleton", msg)
      },
      shardResolver = {
        case msg => "singleton"
      })
  }
}
