package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPoll, EddaPollingProtocol}
import com.netflix.spinnaker.tide.actor.polling.SubnetPollingActor.LatestSubnets
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundSubnets, RetrieveSubnets}
import com.netflix.spinnaker.tide.model.AwsApi.{AwsLocation, Subnet}

class SubnetPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, SubnetPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _

  override def receive: Receive = {
    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveSubnets(location)

    case msg: FoundSubnets =>
      val latestSubnets = LatestSubnets(location, msg.resources)
      clusterSharding.shardRegion(LoadBalancerPollingActor.typeName) ! latestSubnets
      clusterSharding.shardRegion(ServerGroupPollingActor.typeName) ! latestSubnets
  }

}

object SubnetPollingActor extends PollingActorObject {
  val props = Props[SubnetPollingActor]

  case class LatestSubnets(location: AwsLocation, resources: Seq[Subnet]) extends EddaPollingProtocol
}
