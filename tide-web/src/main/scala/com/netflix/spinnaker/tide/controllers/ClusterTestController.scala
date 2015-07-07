package com.netflix.spinnaker.tide.controllers

import akka.actor.ActorRef
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.ClusterTestActor.{NewMessage, Messages, GetMessages, MessageContent}
import com.netflix.spinnaker.tide.actor._
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.{RestController, PathVariable, RequestMapping}
import org.springframework.web.bind.annotation.RequestMethod._
import scala.concurrent.duration.DurationInt

import scala.concurrent.Await

@RestController
class ClusterTestController @Autowired()(private val clusterSharding: ClusterSharding) {

  implicit val timeout = Timeout(5 seconds)

  def clusterTestActor: ActorRef = {
    clusterSharding.shardRegion(ClusterTestActor.typeName)
  }

  @RequestMapping(value = Array("/clusterTest/{id}"), method = Array(GET))
  def all(@PathVariable("id") id: String): List[String] = {
    val future = (clusterTestActor ? GetMessages(id)).mapTo[Messages]
    Await.result(future, timeout.duration).messages
  }

  @RequestMapping(value = Array("/clusterTest/{id}/{message}"), method = Array(GET))
  def sendMessage(@PathVariable("id") id: String, @PathVariable("message") message: String) = {
    clusterTestActor ! NewMessage(id, MessageContent(message))
  }

}
