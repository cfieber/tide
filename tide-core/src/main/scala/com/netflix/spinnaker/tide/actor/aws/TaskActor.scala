package com.netflix.spinnaker.tide.actor.aws

import java.util.Date

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{AwsIdentity, AwsReference}
import com.netflix.spinnaker.tide.actor.aws.TaskActor._

class TaskActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var taskId: String = _
  var taskDescription: TaskDescription = _
  var history: List[Log] = Nil
  var warnings: Set[Warn] = Set()
  var mutations: Set[Mutation] = Set()
  var taskComplete: Option[TaskComplete] = _

  override def receiveCommand: Receive = {
    case event: TaskInit =>
      persist(event)(updateState(_))

    case event: Log =>
      persist(event.copy(timeStamp = new Date().getTime))(updateState(_))

    case event: Warn =>
      persist(event)(updateState(_))

    case event: Mutation =>
      persist(event)(updateState(_))

    case event: TaskComplete =>
      persist(event)(updateState(_))

    case event: GetTask =>
      sender() ! TaskStatus(taskId, taskDescription, history, warnings, mutations, taskComplete)

  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event: TaskProtocol =>
      updateState(event)
  }

  def updateState(event: Any) = {
    event match {
      case event: TaskInit =>
        taskDescription = event.description

      case event: Log =>
        taskId = event.taskId
        history ::= event

      case event: Warn =>
        taskId = event.taskId
        warnings += event

      case event: Create =>
        taskId = event.taskId
        mutations += event

      case event: TaskComplete =>
        taskComplete = Option(event)
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
  case class Create(taskId: String, AwsReference: AwsReference[_ <: AwsIdentity]) extends Mutation {
    val operation = "create"
  }

  case class GetTask(taskId: String) extends TaskProtocol
  case class TaskStatus(taskId: String, taskDescription: TaskDescription, history: List[Log], warnings: Set[Warn], mutations: Set[Mutation],
                        taskComplete: Option[TaskComplete]) extends TaskProtocol

  sealed trait TaskComplete extends TaskProtocol {
    def taskId: String
    def description: TaskDescription
  }
  case class TaskFailure(taskId: String, description: TaskDescription, message: String) extends TaskComplete
  case class TaskSuccess(taskId: String, description: TaskDescription, result: TaskResult) extends TaskComplete
  trait TaskResult
  trait TaskDescription {
    def taskType: String
  }
  case class TaskInit(taskId: String, description: TaskDescription) extends TaskProtocol

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

