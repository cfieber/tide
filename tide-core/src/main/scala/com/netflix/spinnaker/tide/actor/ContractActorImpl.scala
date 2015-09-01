package com.netflix.spinnaker.tide.actor

import akka.actor.ActorRef
import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import scala.concurrent.Await
import akka.pattern.ask
import scala.concurrent.duration.DurationInt

trait ContractActorImpl[ProtocolType] {

  val clusterSharding: ClusterSharding
  val actorObject: ClusteredActorObject

  def actor: ActorRef = clusterSharding.shardRegion(actorObject.typeName)

  implicit val timeout = Timeout(10 seconds)

  def askActor[ReturnType](msg: ProtocolType, returnType: Class[ReturnType]): ReturnType = {
    Await.result(actor ? msg, timeout.duration).asInstanceOf[ReturnType]
  }

  def sendToActor(msg: ProtocolType) = {
    actor ! msg
  }

}