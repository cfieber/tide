package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPollingProtocol, EddaPoll}
import com.netflix.spinnaker.tide.actor.polling.LaunchConfigPollingActor.LatestLaunchConfigs
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundLaunchConfigurations, RetrieveLaunchConfigurations}
import com.netflix.spinnaker.tide.model.AwsApi.{LaunchConfiguration, AwsLocation}

class LaunchConfigPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, LaunchConfigPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _

  override def receive: Receive = {
    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveLaunchConfigurations(location)

    case msg: FoundLaunchConfigurations =>
      val latestLaunchConfigs = LatestLaunchConfigs(location, msg.resources)
      clusterSharding.shardRegion(ServerGroupPollingActor.typeName) ! latestLaunchConfigs
  }

}

object LaunchConfigPollingActor extends PollingActorObject {
  val props = Props[LaunchConfigPollingActor]

  case class LatestLaunchConfigs(location: AwsLocation, resources: Seq[LaunchConfiguration]) extends EddaPollingProtocol
}
