/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPoll
import com.netflix.spinnaker.tide.actor.polling.LaunchConfigPollingActor.LatestLaunchConfigs
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.SubnetPollingActor.LatestSubnets
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor._
import com.netflix.spinnaker.tide.model.{ClearLatestState, AwsResourceProtocol, ServerGroupLatestState, AwsApi}
import AwsApi._

class ServerGroupPollingActor() extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)
  override def pollScheduler = new PollSchedulerActorImpl(context, ServerGroupPollingActor)

  var location: AwsLocation = _
  var latestSecurityGroups: LatestSecurityGroupIdToNameMappings = _
  var latestVpcs: LatestVpcs = _
  var latestSubnets: LatestSubnets = _
  var latestLaunchConfigs: LatestLaunchConfigs = _

  var currentIds: Seq[AutoScalingGroupIdentity] = Nil

  override def receive: Receive = {
    case msg: LatestSecurityGroupIdToNameMappings =>
      latestSecurityGroups = msg

    case msg: LatestVpcs =>
      latestVpcs = msg

    case msg: LatestSubnets =>
      latestSubnets = msg

    case msg: LatestLaunchConfigs =>
      latestLaunchConfigs = msg

    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveAutoScalingGroups(location)

    case msg: FoundAutoScalingGroups =>
      if (Option(latestSecurityGroups).isDefined
        && Option(latestVpcs).isDefined
        && Option(latestSubnets).isDefined
        && Option(latestLaunchConfigs).isDefined) {
        val autoScalingGroups = msg.resources

        val oldIds = currentIds
        currentIds = autoScalingGroups.map(_.identity)
        val removedIds = oldIds.toSet -- currentIds.toSet
        removedIds.foreach { identity =>
          val reference = AwsReference(location, identity)
          clusterSharding.shardRegion(ServerGroupActor.typeName) ! AwsResourceProtocol(reference, ClearLatestState())
        }

        var launchConfigNameToAutoScalingGroup: Map[String, AutoScalingGroup] = Map()
        autoScalingGroups.foreach { autoScalingGroup =>
          launchConfigNameToAutoScalingGroup += (autoScalingGroup.state.launchConfigurationName -> autoScalingGroup)
        }
        latestLaunchConfigs.resources.foreach { launchConfiguration =>
          val autoScalingGroupOption: Option[AutoScalingGroup] = launchConfigNameToAutoScalingGroup.
            get(launchConfiguration.identity.launchConfigurationName)
          autoScalingGroupOption.foreach { autoScalingGroup =>
            val normalizedLaunchConfigurationState = launchConfiguration.state.
              convertToSecurityGroupNames(latestSecurityGroups.map)
            var normalizedAutoScalingGroupState = autoScalingGroup.state.
              populateVpcAttributes(latestVpcs.resources, latestSubnets.resources)
            val latestState = ServerGroupLatestState(normalizedAutoScalingGroupState, normalizedLaunchConfigurationState)
            clusterSharding.shardRegion(ServerGroupActor.typeName) ! AwsResourceProtocol(AwsReference(location,
              autoScalingGroup.identity), latestState)
          }
        }
      }
  }

}

object ServerGroupPollingActor extends PollingActorObject {
  val props = Props[ServerGroupPollingActor]
}


