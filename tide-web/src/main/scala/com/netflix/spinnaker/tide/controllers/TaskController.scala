package com.netflix.spinnaker.tide.controllers

import akka.actor.ActorRef
import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.TaskActor.{GetTask, TaskStatus}
import com.netflix.spinnaker.tide.actor.aws.TaskDirector.GetRunningTasks
import com.netflix.spinnaker.tide.actor.aws._
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMethod._
import org.springframework.web.bind.annotation._
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

import scala.concurrent.Await

@RequestMapping(value = Array("/task"))
@RestController
class TaskController @Autowired()(private val clusterSharding: ClusterSharding) {

  implicit val timeout = Timeout(5 seconds)

  def deepCopyDirector: ActorRef = {
    clusterSharding.shardRegion(TaskDirector.typeName)
  }

  @RequestMapping(value = Array("/{id}"), method = Array(GET))
  def getTask(@PathVariable("id") id: String): TaskStatus = {
    val future = (deepCopyDirector ? GetTask(id)).mapTo[TaskStatus]
    Await.result(future, timeout.duration)
  }

  @RequestMapping(value = Array("/list"), method = Array(GET))
  def getRunningTaskIds: Set[String] = {
    val future = (deepCopyDirector ? GetRunningTasks()).mapTo[Set[String]]
    Await.result(future, timeout.duration)
  }

}

