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

import java.util.Date

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, DescribeAutoScalingGroupsRequest}
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.{AwsPollingProtocol, AwsPoll}
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.ServerGroupPollingActor.NonclassicLinkedLaunchConfigEc2ClassicInstanceIds
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ServerGroupPollingActor() extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var latestSecurityGroups: Option[LatestSecurityGroupIdToNameMappings] = None
  var latestVpcs: Option[LatestVpcs] = None

  var currentIds: Seq[AutoScalingGroupIdentity] = Nil

  override def receive: Receive = {
    case msg: LatestSecurityGroupIdToNameMappings =>
      latestSecurityGroups = Option(msg)

    case msg: LatestVpcs =>
      latestVpcs = Option(msg)

    case msg: AwsPoll =>
      val location = msg.location
      (latestSecurityGroups, latestVpcs) match {
        case (Some(LatestSecurityGroupIdToNameMappings(_, securityGroupIdToName)), Some(LatestVpcs(_, vpcs, subnets))) =>
          val autoScaling = getAwsServiceProvider(location).getAutoScaling

          val launchConfigurationsFuture = Future {
            retrieveAll { nextToken =>
              val result = autoScaling.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest().withNextToken(nextToken))
              (result.getLaunchConfigurations.map(AwsConversion.launchConfigurationFrom), Option(result.getNextToken))
            }
          }
          val autoScalingGroupsFuture = Future {
            retrieveAll{ nextToken =>
              val result = autoScaling.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withNextToken(nextToken))
              (result.getAutoScalingGroups.map(AwsConversion.autoScalingGroupFrom), Option(result.getNextToken))
            }
          }

          for {
            launchConfigurations <- launchConfigurationsFuture
            autoScalingGroups <- autoScalingGroupsFuture
          } {
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
            var nonClassicLinkedLaunchConfigInstanceIds: Seq[String] = Nil

            launchConfigurations.foreach { launchConfiguration =>
              val autoScalingGroupOption: Option[AutoScalingGroup] = launchConfigNameToAutoScalingGroup.
                get(launchConfiguration.identity.launchConfigurationName)
              autoScalingGroupOption.foreach { autoScalingGroup =>
                val normalizedLaunchConfigurationState = launchConfiguration.state.
                  convertToSecurityGroupNames(securityGroupIdToName)
                var normalizedAutoScalingGroupState = autoScalingGroup.state.
                  populateVpcAttributes(vpcs, subnets)
                val latestState = ServerGroupLatestState(normalizedAutoScalingGroupState, normalizedLaunchConfigurationState)
                clusterSharding.shardRegion(ServerGroupActor.typeName) ! AwsResourceProtocol(AwsReference(location,
                  autoScalingGroup.identity), latestState)
                if (launchConfiguration.state.classicLinkVPCId.isEmpty && autoScalingGroup.state.VPCZoneIdentifier.isEmpty) {
                  val inServiceInstanceIds = autoScalingGroup.instances.filter { instance =>
                    instance.lifecycleState == "InService" && instance.healthStatus == "Healthy"
                  }.map(_.instanceId)
                  nonClassicLinkedLaunchConfigInstanceIds ++= inServiceInstanceIds
                }
              }
            }
            clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) !
              NonclassicLinkedLaunchConfigEc2ClassicInstanceIds(location, nonClassicLinkedLaunchConfigInstanceIds.distinct)
          }

        case _ =>
      }
  }

}

object ServerGroupPollingActor extends PollingActorObject {
  val props = Props[ServerGroupPollingActor]

  case class NonclassicLinkedLaunchConfigEc2ClassicInstanceIds(location: AwsLocation, instanceIds: Seq[String]) extends AwsPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
}


