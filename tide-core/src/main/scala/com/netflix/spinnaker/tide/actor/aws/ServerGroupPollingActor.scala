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

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, ServerGroupLatestState}
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

class ServerGroupPollingActor extends PollingActor {

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()
  var launchConfigNameToAutoScalingGroup: Map[String, AutoScalingGroup] = Map()

  override def poll() = {
      val subnets = eddaService.subnets
      val launchConfigurations = eddaService.launchConfigurations
      val autoScalingGroups = eddaService.autoScalingGroups
      eddaService.securityGroups.foreach { securityGroup =>
        securityGroupIdToName += (securityGroup.groupId -> securityGroup.identity)
      }
      autoScalingGroups.foreach { autoScalingGroup =>
        launchConfigNameToAutoScalingGroup += (autoScalingGroup.state.launchConfigurationName -> autoScalingGroup)
      }
      launchConfigurations.foreach { launchConfiguration =>
        val autoScalingGroupOption: Option[AutoScalingGroup] = launchConfigNameToAutoScalingGroup.get(launchConfiguration.identity.launchConfigurationName)
        autoScalingGroupOption.foreach { autoScalingGroup =>
          val securityGroupNames = launchConfiguration.state.securityGroups.map { securityGroupName =>
            if (securityGroupName.startsWith("sg-")) {
              securityGroupIdToName.get(securityGroupName) match {
                case None => securityGroupName
                case Some(identity) => identity.groupName
              }
            } else {
              securityGroupName
            }
          }
          val normalizedLaunchConfigurationState = launchConfiguration.state.copy(securityGroups = securityGroupNames)
          var normalizedAutoScalingGroupState = autoScalingGroup.state
          val vpcZoneIdentifier = autoScalingGroup.state.VPCZoneIdentifier
          val splitVpcZoneIdentifier = vpcZoneIdentifier.split(",")
          splitVpcZoneIdentifier.headOption.foreach { subnetId =>
            val subnetOption = subnets.find(_.subnetId == subnetId)
            subnetOption.foreach { subnet =>
              normalizedAutoScalingGroupState = normalizedAutoScalingGroupState
                .copy(subnetType = Option(subnet.subnetType), vpcId = Option(subnet.vpcId))
            }
          }
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


