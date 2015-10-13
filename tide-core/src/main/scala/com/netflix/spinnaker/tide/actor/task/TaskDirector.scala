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

package com.netflix.spinnaker.tide.actor.task

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector.{ChildTaskDescriptions, TaskDescription, GetRunningTasks}

import scala.concurrent.duration.DurationInt

class TaskDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  var currentExecutionsByTaskId: Map[String, ExecuteTask] = Map()

  var nextTaskId: Long = 1

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def receiveCommand: Receive = {

    case event: GetRunningTasks =>
      sender() ! currentExecutionsByTaskId.keySet

    case event: GetTask =>
      getShardCluster(TaskActor.typeName) forward event

    case taskDescription: TaskDescription =>
      val executeTask = ExecuteTask(nextTaskId.toString, taskDescription)
      nextTaskId = nextTaskId + 1
      self ! executeTask
      sender() ! executeTask

    case executeTask: ExecuteTask =>
      persist(executeTask) { it =>
        updateState(executeTask)
        getShardCluster(executeTask.description.executionActorTypeName) ! executeTask
        getShardCluster(TaskActor.typeName) ! executeTask
      }

    case childTasks: ChildTaskDescriptions =>
      if (childTasks.descriptions.isEmpty) {
        sender() ! ChildTaskGroupComplete(childTasks.parentTaskId, Nil)
      }
      val taskCluster = getShardCluster(TaskActor.typeName)
      val executeTasks: Seq[ExecuteTask] = childTasks.descriptions.map { taskDescription =>
        val executeTask = ExecuteTask(nextTaskId.toString, taskDescription, Option(childTasks.parentTaskId))
        nextTaskId = nextTaskId + 1
        self ! executeTask
        executeTask
      }
      val executeChildTasks = ExecuteChildTasks(childTasks.parentTaskId, executeTasks)
      sender() ! executeChildTasks
      taskCluster ! executeChildTasks

    case event: TaskComplete =>
      persist(event) { it =>
        updateState(it)
      }
  }

  override def receiveRecover: Receive = {
    case event: RecoveryCompleted =>
      currentExecutionsByTaskId.foreach {
        case (id, executeTask) =>
          getShardCluster(executeTask.description.executionActorTypeName) ! ContinueTask(executeTask)
      }
    case event: ExecuteTask =>
      updateState(event)
      nextTaskId = event.taskId.toInt + 1
    case event =>
      updateState(event)
  }

  def updateState(event: Any) = {
    event match {
      case event: ExecuteTask =>
        currentExecutionsByTaskId += (event.taskId -> event)
      case event: TaskComplete =>
        currentExecutionsByTaskId -= event.taskId
    }
  }
}

sealed trait TaskDirectorProtocol extends Serializable

object TaskDirector extends SingletonActorObject {
  val props = Props[TaskDirector]

  case class GetRunningTasks() extends TaskDirectorProtocol
  trait TaskDescription {
    def taskType: String
    def executionActorTypeName: String
  }
  case class ChildTaskDescriptions(parentTaskId: String, descriptions: Seq[TaskDescription]) extends TaskDirectorProtocol

}
