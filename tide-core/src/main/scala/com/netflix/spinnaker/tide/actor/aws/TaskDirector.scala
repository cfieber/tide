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
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.AwsResourceReference
import com.netflix.spinnaker.tide.actor.aws.DependencyCopyActor.DependencyCopyTask
import com.netflix.spinnaker.tide.actor.aws.ServerGroupCloneActor.ServerGroupDeepCopyTask
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector.typeName
import scala.concurrent.duration.DurationInt

class TaskDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  var awsResource: AwsResourceActor.Ref = _

  var currentTasksById: Map[String, TaskDescription] = Map()

  var nextTaskId: Long = 1

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
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
      getShardCluster(TaskActor.typeName) forward event

    case event: TaskDescription =>
      persist(event) { it =>
        sender() ! TaskStatus(nextTaskId.toString, Nil, Set(), Set(), None)
        routeTask(it)
        updateState(it)
      }

    case event: TaskComplete =>
      persist(event) { it =>
        updateState(it)
        getShardCluster(TaskActor.typeName) ! event
      }
  }

  override def receiveRecover: Receive = {
    case event: RecoveryCompleted => Nil
    case event =>
      updateState(event)
  }

  def routeTask(taskDescription: TaskDescription, isContinued: Boolean = false, id: String = nextTaskId.toString): Unit = {
    taskDescription match {
      case event : ServerGroupDeepCopyTask =>
        getShardCluster(ServerGroupCloneActor.typeName) ! ExecuteTask(id, awsResource, taskDescription, isContinued)
      case event : DependencyCopyTask =>
        getShardCluster(DependencyCopyActor.typeName) ! ExecuteTask(id, awsResource, taskDescription, isContinued)
    }
  }

  def updateState(event: Any) = {
    event match {
      case event: TaskDescription =>
        currentTasksById += (nextTaskId.toString -> event)
        nextTaskId = nextTaskId + 1
      case event: TaskComplete =>
        currentTasksById -= event.taskId
    }
  }
}

sealed trait TaskDirectorProtocol extends Serializable

object TaskDirector {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class GetRunningTasks() extends TaskDirectorProtocol

  case class ExecuteTask(taskId: String, awsResource: ActorRef, description: TaskDescription, isContinued: Boolean = false)
    extends TaskDirectorProtocol with AkkaClustered {
    val akkaIdentifier: String = s"${description.taskType}.$taskId"
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
