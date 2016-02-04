package com.netflix.spinnaker.tide.actor.task

import java.util.Date

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion.Passivate
import akka.pattern.ask
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.TaskActorObject
import com.netflix.spinnaker.tide.actor.task.TaskActor.{CancelTask, RestartTask, TaskCancel, Log, Mutation, GetTask, TaskStatus, ExecuteChildTasks, ExecuteTask, ContinueTask, TaskComplete, ChildTaskComplete, ChildTaskGroupComplete}
import com.netflix.spinnaker.tide.actor.task.TaskDirector.TaskDescription

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class TaskActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  // passivate the entity when no activity
  context.setReceiveTimeout(120 seconds)

  var taskId: String = _
  var parentTaskId: Option[String] = _
  var taskDescription: TaskDescription = _
  var history: List[Log] = Nil
  var mutations: Set[Mutation] = Set()
  var taskComplete: Option[TaskComplete] = _

  var childTasks: Map[String, TaskDescription] = Map()
  var childTaskGroups: List[Seq[String]] = Nil
  var childTasksComplete: Map[String, TaskComplete] = Map()

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {
    case event: ExecuteTask =>
      persist(event)(updateState(_))

    case event: ContinueTask =>
      persist(event)(updateState(_))

    case event: ExecuteChildTasks =>
      persist(event)(updateState(_))

    case ReceiveTimeout =>
      if (taskComplete.nonEmpty) {
        context.parent ! Passivate(stopMessage = PoisonPill)
      }

    case event: Log =>
      persist(event) { it =>
        updateState(it)
        parentTaskId match {
          case None => Nil
          case Some(id) => getShardCluster(TaskActor.typeName) ! event.copy(taskId = id, childTaskId = Option(taskId))
        }
      }

    case event: Mutation =>
      persist(event) { it =>
        updateState(it)
        parentTaskId match {
          case None => Nil
          case Some(id) => getShardCluster(TaskActor.typeName) ! event.copy(taskId = id, childTaskId = Option(taskId))
        }
      }

    case event: TaskComplete =>
      persist(event){ taskComplete =>
        updateState(taskComplete)
        parentTaskId.foreach(taskId => getShardCluster(TaskActor.typeName) ! ChildTaskComplete(taskId, event))
        getShardCluster(TaskDirector.typeName) ! event
      }

    case event: ChildTaskComplete =>
      persist(event) { childTaskComplete =>
        updateState(childTaskComplete)
        val childTaskGroupOption: Option[Seq[String]] = childTaskGroups.find(_.contains(event.taskComplete.taskId))
        childTaskGroupOption.foreach { childTaskGroup =>
          val completedChildTaskGroup: Map[String, Option[TaskComplete]] = childTaskGroup.map { childTaskId =>
            childTaskId -> childTasksComplete.get(childTaskId)
          }.toMap
          if (completedChildTaskGroup.values.forall(_.isDefined)) {
            val childTaskCompletes: Seq[TaskComplete] = completedChildTaskGroup.values.flatten.toList
            getShardCluster(taskDescription.executionActorTypeName) ! ChildTaskGroupComplete(taskId, childTaskCompletes)
          }
        }
      }

    case event: CancelTask =>
      self ! TaskCancel(taskId, taskDescription, event.canceledBy)
      childTasks.keySet.foreach(CancelTask(_, taskId))

    case event: RestartTask =>
      val future = (getShardCluster(TaskDirector.typeName) ? taskDescription).mapTo[ExecuteTask]
      sender ! Await.result(future, timeout.duration).taskId

    case event: GetTask =>
      val sortedHistory = history.sortBy(_.timeStamp)
      sender() ! TaskStatus(taskId, parentTaskId, taskDescription, childTasks.keySet, sortedHistory,
        mutations, taskComplete)

  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event: TaskProtocol =>
      updateState(event)
  }

  def updateState(msg: Any) = {
    msg match {
      case event: ExecuteTask =>
        taskId = event.taskId
        parentTaskId = event.parentTaskId
        taskDescription = event.description

      case event: ExecuteChildTasks =>
        event.executeTasks.foreach { executeTask =>
          childTasks += (executeTask.taskId -> executeTask.description)
        }
        childTaskGroups ::= event.executeTasks.map(_.taskId)

      case event: Log =>
        history ::= event

      case event: Mutation =>
        mutations += event

      case event: TaskComplete =>
        taskComplete = Option(event)

      case event: ChildTaskComplete =>
        childTasksComplete += (event.taskComplete.taskId -> event.taskComplete)

      case event => Nil
    }
  }

}

sealed trait TaskProtocol extends Serializable {
  def taskId: String
}

object TaskActor extends TaskActorObject {
  val props = Props[TaskActor]

  case class Log(taskId: String, message: String, childTaskId: Option[String] = None) extends TaskProtocol {
    val timeStamp: Long = new Date().getTime
  }

  case class Mutation(taskId: String, mutationType: MutationType, mutationDetails: MutationDetails,
                      childTaskId: Option[String] = None) extends TaskProtocol

  sealed trait MutationType {
    def name: String
  }
  case class Create() extends MutationType {
    val name: String = "create"
  }

  trait MutationDetails

  case class GetTask(taskId: String) extends TaskProtocol
  case class CancelTask(taskId: String, canceledBy: String) extends TaskProtocol
  case class RestartTask(taskId: String) extends TaskProtocol
  case class TaskStatus(taskId: String, parentTaskId: Option[String], taskDescription: TaskDescription,
                        childTasks: Set[String], history: Seq[Log], mutations: Set[Mutation],
                        taskComplete: Option[TaskComplete]) extends TaskProtocol

  case class ExecuteChildTasks(parentTaskId: String, executeTasks: Seq[ExecuteTask]) extends TaskProtocol {
    def taskId = parentTaskId
  }
  case class ExecuteTask(taskId: String, description: TaskDescription, parentTaskId: Option[String] = None) extends TaskProtocol

  case class ContinueTask(executeTask: ExecuteTask) extends TaskProtocol {
    def taskId = executeTask.taskId
  }

  sealed trait TaskComplete extends TaskProtocol {
    def taskId: String
    def status: String
    def description: TaskDescription
  }
  case class TaskFailure(taskId: String, description: TaskDescription, message: String,
                         reason: Option[Throwable] = None) extends TaskComplete {
    val status = "failure"
  }
  case class TaskSuccess(taskId: String, description: TaskDescription, result: TaskResult) extends TaskComplete {
    val status = "success"
  }
  case class TaskCancel(taskId: String, description: TaskDescription, canceledBy: String) extends TaskComplete {
    val status = "cancel"
  }

  trait TaskResult

  case class NoResult() extends TaskResult

  trait ChildTaskProtocol extends TaskProtocol {
    def parentTaskId: String
    val taskId = parentTaskId
  }

  case class ChildTaskComplete(parentTaskId: String, taskComplete: TaskComplete) extends ChildTaskProtocol
  case class ChildTaskGroupComplete(parentTaskId: String, taskCompletes: Seq[TaskComplete]) extends ChildTaskProtocol

}

