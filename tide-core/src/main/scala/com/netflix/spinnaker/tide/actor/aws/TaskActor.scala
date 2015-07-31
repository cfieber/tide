package com.netflix.spinnaker.tide.actor.aws

import java.util.Date

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{AwsIdentity, AwsReference}
import com.netflix.spinnaker.tide.actor.aws.TaskActor._
import com.netflix.spinnaker.tide.actor.aws.TaskDirector.TaskDescription
import akka.pattern.ask
import com.netflix.spinnaker.tide.api.PipelineState
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class TaskActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  var taskId: String = _
  var parentTaskId: Option[String] = _
  var taskDescription: TaskDescription = _
  var history: List[Log] = Nil
  var warnings: Set[Warn] = Set()
  var mutations: Set[Mutation] = Set()
  var taskComplete: Option[TaskComplete] = _

  var childTasks: Map[String, TaskDescription] = Map()
  var childTaskGroups: List[List[String]] = Nil
  var childTasksComplete: Map[String, TaskComplete] = Map()

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def receiveCommand: Receive = {
    case event: ExecuteTask =>
      persist(event)(updateState(_))

    case event: ContinueTask =>
      persist(event)(updateState(_))

    case event: ExecuteChildTasks =>
      persist(event)(updateState(_))

    case event: Log =>
      persist(event.copy(timeStamp = new Date().getTime))(updateState(_))

    case event: Warn =>
      persist(event)(updateState(_))

    case event: Mutation =>
      persist(event)(updateState(_))

    case event: TaskComplete =>
      persist(event){ taskComplete =>
        updateState(taskComplete)
        parentTaskId.foreach(taskId => getShardCluster(TaskActor.typeName) ! ChildTaskComplete(taskId, event))
        getShardCluster(TaskDirector.typeName) ! event
      }

    case event: ChildTaskComplete =>
      persist(event) { childTaskComplete =>
        updateState(childTaskComplete)
        val childTaskGroupOption: Option[List[String]] = childTaskGroups.find(_.contains(event.taskComplete.taskId))
        childTaskGroupOption.foreach { childTaskGroup =>
          val completedChildTaskGroup: Map[String, Option[TaskComplete]] = childTaskGroup.map { childTaskId =>
            childTaskId -> childTasksComplete.get(childTaskId)
          }.toMap
          if (completedChildTaskGroup.values.forall(_.isDefined)) {
            val childTaskCompletes: List[TaskComplete] = completedChildTaskGroup.values.flatten.toList
            getShardCluster(taskDescription.executionActorTypeName) ! ChildTaskGroupComplete(taskId, childTaskCompletes)
          }
        }
      }

    case event: GetTask =>
      var allHistory: List[Log] = history
      var allWarnings: Set[Warn] = warnings
      var allMutations: Set[Mutation] = mutations
      childTasks.keySet.foreach { childTaskId =>
        val future = (getShardCluster(TaskDirector.typeName) ? GetTask(childTaskId)).mapTo[TaskStatus]
        val childTaskStatus: TaskStatus = Await.result(future, timeout.duration)
        allHistory :::= childTaskStatus.history
        allWarnings ++= childTaskStatus.warnings
        allMutations ++= childTaskStatus.mutations
      }
      val sortedHistory = allHistory.sortBy(_.timeStamp)
      sender() ! TaskStatus(taskId, parentTaskId, taskDescription, childTasks.keySet, sortedHistory, allWarnings,
        allMutations, taskComplete)

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

      case event: Warn =>
        warnings += event

      case event: Create =>
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

object TaskActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class Log(taskId: String, message: String, timeStamp: Long = 0) extends TaskProtocol
  case class Warn(taskId: String, AwsReference: AwsReference[_ <: AwsIdentity], message: String) extends TaskProtocol

  sealed trait Mutation extends TaskProtocol
  sealed trait Create extends Mutation{
    val operation = "create"
  }
  case class CreateAwsResource(taskId: String, AwsReference: AwsReference[_ <: AwsIdentity], objectToCreate: Option[Any]= None) extends Create
  case class CreatePipeline(taskId: String, pipelineToCreate: PipelineState) extends Create

  case class GetTask(taskId: String) extends TaskProtocol
  case class TaskStatus(taskId: String, parentTaskId: Option[String], taskDescription: TaskDescription,
                        childTasks: Set[String], history: List[Log], warnings: Set[Warn], mutations: Set[Mutation],
                        taskComplete: Option[TaskComplete]) extends TaskProtocol

  case class ExecuteChildTasks(parentTaskId: String, executeTasks: List[ExecuteTask]) extends TaskProtocol {
    def taskId = parentTaskId
  }
  case class ExecuteTask(taskId: String, cloudResourceRef: ActorRef, description: TaskDescription,
                         parentTaskId: Option[String] = None) extends TaskProtocol

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

  trait TaskResult

  trait ChildTaskProtocol extends TaskProtocol {
    def parentTaskId: String
    val taskId = parentTaskId
  }

  case class ChildTaskComplete(parentTaskId: String, taskComplete: TaskComplete) extends ChildTaskProtocol
  case class ChildTaskGroupComplete(parentTaskId: String, taskCompletes: List[TaskComplete]) extends ChildTaskProtocol

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[TaskActor]),
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

