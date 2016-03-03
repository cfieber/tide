package com.netflix.spinnaker.tide.actor.comparison

import akka.actor.{Props, ActorLogging}
import akka.persistence.{RecoveryFailure, RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.LoadBalancerActor.DiffLoadBalancer
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{DiffAttributes, GetDiff}
import com.netflix.spinnaker.tide.model._

class AttributeDiffActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var attributeDiff: AttributeDiff[Any] = AttributeDiff.forIdentifierType(classOf[Any])

  override def receiveCommand: Receive = {
    case msg: DiffAttributes[_] =>
      persist(msg) { it =>
        updateState(it)
      }
    case msg: GetDiff =>
      sender() ! attributeDiff
  }

  private def updateState(comparable: DiffAttributes[_]) = {
    comparable.attributes match {
      case Some(attributes) =>
        attributeDiff = attributeDiff.compareResource(comparable.identity, attributes)
      case None =>
        attributeDiff = attributeDiff.removeResource(comparable.identity)
    }
  }

  override def receiveRecover: Receive = {
    case msg: RecoveryFailure => log.error(msg.cause, msg.cause.toString)
    case event: DiffAttributes[_] =>
      updateState(event)
  }

}

trait AttributeDiffProtocol extends AkkaClustered

object AttributeDiffActor extends ClusteredActorObject {
  val props = Props[AttributeDiffActor]

  trait DiffAttributes[T] extends AttributeDiffProtocol {
    def identity: T
    def attributes: Option[Product]
  }

  trait GetDiff extends AttributeDiffProtocol
}

