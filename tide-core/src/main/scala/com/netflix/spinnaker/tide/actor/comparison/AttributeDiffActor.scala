package com.netflix.spinnaker.tide.actor.comparison

import akka.actor.{Actor, Props, ActorLogging}
import akka.persistence.{RecoveryFailure, RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.LoadBalancerActor.DiffLoadBalancer
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{DiffAttributes, GetDiff}
import com.netflix.spinnaker.tide.model._

class AttributeDiffActor extends Actor with ActorLogging {

  var attributeDiff: AttributeDiff[Any] = AttributeDiff.forIdentifierType(classOf[Any])

  override def receive: Receive = {
    case msg: DiffAttributes[_] =>
      msg.attributes match {
        case Some(attributes) =>
          attributeDiff = attributeDiff.compareResource(msg.identity, attributes)
        case None =>
          attributeDiff = attributeDiff.removeResource(msg.identity)
      }

    case msg: GetDiff =>
      sender() ! attributeDiff
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

