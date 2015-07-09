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
  var history: List[Log] = Nil
  var warnings: List[Warn] = Nil
  var mutations: List[Mutation] = Nil

  override def receiveCommand: Receive = {
    case event: Log =>
      persist(event.copy(timeStamp = new Date().getTime))(updateState(_))

    case event: Warn =>
      persist(event)(updateState(_))

    case event: Create =>
      persist(event)(updateState(_))

    case event: GetTask =>
      sender() ! TaskStatus(taskId, history, warnings, mutations)

  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event: TaskProtocol =>
      updateState(event)
  }

  def updateState(event: TaskProtocol) = {
    taskId = event.taskId
    event match {
      case event: Log =>
        history ::= event

      case event: Warn =>
        warnings ::= event

      case event: Create =>
        mutations ::= event
    }
  }

}

sealed trait TaskProtocol {
  def taskId: String
}

object TaskActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  case class Log(taskId: String, message: String, timeStamp: Long = 0) extends TaskProtocol
  case class Warn(taskId: String, AwsReference: AwsReference[AwsIdentity], message: String) extends TaskProtocol

  sealed trait Mutation extends TaskProtocol
  case class Create(taskId: String, AwsReference: AwsReference[AwsIdentity]) extends Mutation

  case class GetTask(taskId: String) extends TaskProtocol
  case class TaskStatus(taskId: String, history: List[Log], warnings: List[Warn], mutations: List[Mutation]) extends TaskProtocol

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[DeepCopyActor]),
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

