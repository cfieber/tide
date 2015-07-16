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

package com.netflix.spinnaker.tide.actor.aws

import akka.actor.{Props, ActorRef}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, ServerGroupLatestState}
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.aws.SubnetPollingActor.GetSubnets
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import akka.pattern.ask
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await


class ServerGroupPollingActor extends PollingActor {
  implicit val timeout = Timeout(5 seconds)

  override def poll() = {
    val vpcsFuture = (getShardCluster(VpcPollingActor.typeName) ? GetVpcs(account, region)).mapTo[List[Vpc]]
    val subnetsFuture = (getShardCluster(SubnetPollingActor.typeName) ? GetSubnets(account, region)).mapTo[List[Subnet]]
    val securityGroupsFuture = (getShardCluster(SecurityGroupPollingActor.typeName) ? GetSecurityGroupIdToNameMappings(account, region)).mapTo[Map[String, SecurityGroupIdentity]]
    val securityGroupIdToName: Map[String, SecurityGroupIdentity] = Await.result(securityGroupsFuture, timeout.duration)
    val vpcs = Await.result(vpcsFuture, timeout.duration)
    val subnets = Await.result(subnetsFuture, timeout.duration)
    val launchConfigurations = eddaService.launchConfigurations
    val autoScalingGroups = eddaService.autoScalingGroups

    var launchConfigNameToAutoScalingGroup: Map[String, AutoScalingGroup] = Map()
      autoScalingGroups.foreach { autoScalingGroup =>
        launchConfigNameToAutoScalingGroup += (autoScalingGroup.state.launchConfigurationName -> autoScalingGroup)
      }
      launchConfigurations.foreach { launchConfiguration =>
        val autoScalingGroupOption: Option[AutoScalingGroup] = launchConfigNameToAutoScalingGroup.get(launchConfiguration.identity.launchConfigurationName)
        autoScalingGroupOption.foreach { autoScalingGroup =>
          val normalizedLaunchConfigurationState = launchConfiguration.state.convertToSecurityGroupNames(securityGroupIdToName)
          var normalizedAutoScalingGroupState = autoScalingGroup.state.populateVpcAttributes(vpcs, subnets)
          val latestState = ServerGroupLatestState(normalizedAutoScalingGroupState, normalizedLaunchConfigurationState)
          resourceCluster(ServerGroupActor.typeName) ! AwsResourceProtocol(AwsReference(AwsLocation(account, region), autoScalingGroup.identity),
            latestState, Option(cloudDriver))
        }
      }
  }
}

object ServerGroupPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[ServerGroupPollingActor]
}


