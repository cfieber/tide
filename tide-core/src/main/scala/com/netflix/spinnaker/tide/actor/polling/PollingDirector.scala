package com.netflix.spinnaker.tide.actor.polling


import akka.actor.{Actor, ActorRef, Props}
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.ClassicLinkSecurityGroupNames
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.{AccountMetaData, AwsPoll}
import com.netflix.spinnaker.tide.actor.polling.PipelinePollingActor.PipelinePoll
import com.netflix.spinnaker.tide.actor.polling.PollingDirector.{Poll, PollInit}
import com.netflix.spinnaker.tide.config.ClassicLinkSettings
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

import scala.collection.JavaConverters._

import scala.concurrent.duration.DurationInt

class PollingDirector extends Actor {

  var pollInit: Option[PollInit] = None

  private implicit val dispatcher = context.dispatcher
  val tick = context.system.scheduler.schedule(0 seconds, 15 seconds, self, Poll())

  override def postStop() = {
    tick.cancel()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def receive: Receive = {
    case event: PollInit =>
        pollInit = Option(event)
        context.become(polling(event))
    case _ =>
  }

  def polling(pollInit: PollInit): Receive = {
    case poll: Poll =>
      getShardCluster(PipelinePollingActor.typeName) ! PipelinePoll()
      val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, ClassicLinkInstanceIdPollingActor,
        SecurityGroupPollingActor, LoadBalancerPollingActor, ServerGroupPollingActor)
      val accountNames: Set[String] = pollInit.accountNamesToRegionNames.keySet
      for (accountName <- accountNames) {
        val regionNames = pollInit.accountNamesToRegionNames(accountName)
        for (regionName <- regionNames) {
          val location = AwsLocation(accountName, regionName)
          for (poller <- pollers) {
            getShardCluster(poller.typeName) ! AwsPoll(location, pollInit.accountMetaData)
          }
          val classicLinkSettings = pollInit.classicLinkSettings
          if (classicLinkSettings.accounts.contains(location.account)) {
            getShardCluster(ClassicLinkInstancesActor.typeName) ! ClassicLinkSecurityGroupNames(location,
              classicLinkSettings.getSecurityGroups.asScala)
          }
        }
      }
    case _ =>
  }

}

sealed trait PollingDirectorProtocol extends Serializable

object PollingDirector extends SingletonActorObject {
  val props = Props[PollingDirector]

  case class PollInit(accountNamesToRegionNames: Map[String, Set[String]], classicLinkSettings: ClassicLinkSettings,
                      accountMetaData: AccountMetaData) extends PollingDirectorProtocol
  case class Poll() extends PollingDirectorProtocol

}
