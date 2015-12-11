package com.netflix.spinnaker.tide.actor

import java.util.Date

import akka.actor.{Actor, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.ContinuousInitActor.Tick
import com.netflix.spinnaker.tide.actor.polling.PollingDirector
import com.netflix.spinnaker.tide.actor.polling.PollingDirector.PollInit
import com.netflix.spinnaker.tide.actor.task.TaskDirector
import com.netflix.spinnaker.tide.actor.task.TaskDirector.GetRunningTasks
import scala.concurrent.duration.DurationInt

class ContinuousInitActor(clusterSharding: ClusterSharding,
                          accountsToRegions: Map[String, Set[String]],
                          classicLinkSecurityGroupNames: Seq[String]) extends Actor with ActorLogging {

  private implicit val dispatcher = context.dispatcher
  val tick = context.system.scheduler.schedule(0 seconds, 5 seconds, self, Tick())

  override def postStop() = {
    tick.cancel()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receive = {
    case t: Tick =>
      clusterSharding.shardRegion(PollingDirector.typeName) ! PollInit(accountsToRegions, classicLinkSecurityGroupNames)
      clusterSharding.shardRegion(TaskDirector.typeName) ! GetRunningTasks()
    case _ =>
  }

}

object ContinuousInitActor {
  case class Tick()
}