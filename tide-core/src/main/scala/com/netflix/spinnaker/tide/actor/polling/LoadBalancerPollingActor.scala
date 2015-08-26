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
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.SubnetPollingActor.GetSubnets
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.GetVpcs
import com.netflix.spinnaker.tide.actor.service.EddaActor.RetrieveLoadBalancers
import com.netflix.spinnaker.tide.model.{AwsResourceProtocol, LoadBalancerLatestState, AwsApi}
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws._

class LoadBalancerPollingActor() extends EddaPollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)
  override def pollScheduler = new PollSchedulerActorImpl(context, VpcPollingActor)

  val vpcPolling: VpcPollingContract = new VpcPollingContractActor(clusterSharding)
  val subnetPolling: SubnetPollingContract = new SubnetPollingContractActor(clusterSharding)
  val securityGroupPolling: SecurityGroupPollingContract = new SecurityGroupPollingContractActor(clusterSharding)

  override def handlePoll(location: AwsLocation): Unit = {
    val loadBalancers = edda.ask(RetrieveLoadBalancers(location)).resources

    val latestVpcs = vpcPolling.ask(GetVpcs(location))
    val latestSubnets = subnetPolling.ask(GetSubnets(location))
    val latestSecurityGroups = securityGroupPolling.ask(GetSecurityGroupIdToNameMappings(location))
    loadBalancers.foreach { loadBalancer =>
      var normalizedState = loadBalancer.state.convertToSecurityGroupNames(latestSecurityGroups.map).
        populateVpcAttributes(latestVpcs.resources, latestSubnets.resources)
      val reference = AwsReference(location, loadBalancer.identity)
      val latestState = LoadBalancerLatestState(normalizedState)
      clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, latestState)
    }
  }
}

object LoadBalancerPollingActor extends PollingActorObject {
  val props = Props[LoadBalancerPollingActor]
}

