package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.aws.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPollingProtocol, EddaPoll}
import com.netflix.spinnaker.tide.actor.polling.ClassicLinkInstanceIdPollingActor.LatestClassicLinkInstanceIds
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundClassicLinkInstanceIds, RetrieveClassicLinkInstanceIds}
import com.netflix.spinnaker.tide.model.AkkaClustered
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

class ClassicLinkInstanceIdPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, ClassicLinkInstanceIdPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _

  override def receive: Receive = {
    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveClassicLinkInstanceIds(location)

    case msg: FoundClassicLinkInstanceIds =>
      val instanceIds = LatestClassicLinkInstanceIds(location, msg.resources)
      clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) ! instanceIds
  }

}

object ClassicLinkInstanceIdPollingActor extends PollingActorObject {
  val props = Props[ClassicLinkInstanceIdPollingActor]
  case class LatestClassicLinkInstanceIds(location: AwsLocation, resources: Seq[String]) extends EddaPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
}
