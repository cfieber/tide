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
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPoll
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.SubnetPollingActor.LatestSubnets
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundLoadBalancers, RetrieveLoadBalancers}
import com.netflix.spinnaker.tide.model.{ClearLatestState, AwsResourceProtocol, LoadBalancerLatestState, AwsApi}
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws._

class LoadBalancerPollingActor() extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)
  override def pollScheduler = new PollSchedulerActorImpl(context, LoadBalancerPollingActor)

  var location: AwsLocation = _
  var latestSecurityGroups: LatestSecurityGroupIdToNameMappings = _
  var latestVpcs: LatestVpcs = _
  var latestSubnets: LatestSubnets = _

  var currentIds: Seq[LoadBalancerIdentity] = Nil

  override def receive: Receive = {
    case msg: LatestSecurityGroupIdToNameMappings =>
      latestSecurityGroups = msg

    case msg: LatestVpcs =>
      latestVpcs = msg

    case msg: LatestSubnets =>
      latestSubnets = msg

    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveLoadBalancers(location)

    case msg: FoundLoadBalancers =>
      if (Option(latestSecurityGroups).isDefined
        && Option(latestVpcs).isDefined
        && Option(latestSubnets).isDefined) {
        val loadBalancers = msg.resources

        val oldIds = currentIds
        currentIds = loadBalancers.map(_.identity)
        val removedIds = oldIds.toSet -- currentIds.toSet
        removedIds.foreach { identity =>
          val reference = AwsReference(location, identity)
          clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, ClearLatestState())
        }

        loadBalancers.foreach { loadBalancer =>
          var normalizedState = loadBalancer.state.convertToSecurityGroupNames(latestSecurityGroups.map).
            populateVpcAttributes(latestVpcs.resources, latestSubnets.resources)
          val reference = AwsReference(location, loadBalancer.identity)
          val latestState = LoadBalancerLatestState(normalizedState)
          clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, latestState)
        }
      }
  }
}

object LoadBalancerPollingActor extends PollingActorObject {
  val props = Props[LoadBalancerPollingActor]
}

