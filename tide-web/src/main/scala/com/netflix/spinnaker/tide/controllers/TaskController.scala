package com.netflix.spinnaker.tide.controllers

import java.util.concurrent.TimeoutException
import javax.servlet.http.HttpServletRequest

import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskDirector}
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import TaskDirector.GetRunningTasks
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
  implicit val maxRetryCount = 3


  @RequestMapping(value = Array("/{id}"), method = Array(GET))
  def getTask(@PathVariable("id") id: String): TaskStatus = {
    retrieveTask(id, 0)
  }

  @RequestMapping(value = Array("/restart/{id}"), method = Array(GET))
  def restartTask(@PathVariable("id") id: String): String = {
    val future = (clusterSharding.shardRegion(TaskActor.typeName) ? RestartTask(id)).mapTo[String]
    Await.result(future, timeout.duration)
  }

  @RequestMapping(value = Array("/cancel/{id}"), method = Array(GET))
  def cancelTask(@PathVariable("id") id: String, request: HttpServletRequest) = {
    clusterSharding.shardRegion(TaskActor.typeName) ! CancelTask(id, request.getRemoteAddr)
  }

  @RequestMapping(value = Array("/list"), method = Array(GET))
  def getRunningTaskIds: Map[String, String] = {
    val future = (clusterSharding.shardRegion(TaskDirector.typeName) ? GetRunningTasks()).mapTo[Iterable[ExecuteTask]]
    val tasks = Await.result(future, timeout.duration).toList
    tasks.sortBy(_.taskId).map { task =>
      task.taskId -> task.description.summary
    }.toMap
  }

  def retrieveTask(id: String, retriesAttempted: Int): TaskStatus = {
    val future = (clusterSharding.shardRegion(TaskActor.typeName) ? GetTask(id)).mapTo[TaskStatus]
    // For some reason, this call intermittently throws TimeoutExceptions. We retry, since Orca reads it as a failure,
    // and reports to the UI that the task failed, even though it's probably continuing to create artifacts, which is
    // a very bad thing
    try {
      Await.result(future, timeout.duration)
    } catch {
      case te: TimeoutException =>
        if (retriesAttempted >= maxRetryCount) {
          throw te
        }
        retrieveTask(id, retriesAttempted + 1)
    }
  }

}

