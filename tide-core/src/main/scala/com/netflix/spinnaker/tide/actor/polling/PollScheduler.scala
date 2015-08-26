package com.netflix.spinnaker.tide.actor.polling

import akka.actor.ActorContext
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.polling.PollingActor.Poll
import scala.concurrent.duration.{FiniteDuration, DurationInt}

class PollSchedulerActorImpl(context: ActorContext, actorObject: ClusteredActorObject,
                             delay: FiniteDuration = 15 seconds) extends PollScheduler {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  def scheduleNextPoll(poll: Poll): Unit = {
    scheduler.scheduleOnce(delay, clusterSharding.shardRegion(actorObject.typeName), poll)
  }
}

trait PollScheduler {
  def scheduleNextPoll(poll: Poll): Unit
}