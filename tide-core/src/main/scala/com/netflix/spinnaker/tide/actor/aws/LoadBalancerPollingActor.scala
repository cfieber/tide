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
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, LoadBalancerLatestState}
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.aws.SubnetPollingActor.GetSubnets
import akka.pattern.ask
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import scala.concurrent.duration.DurationInt

import scala.concurrent.Await

class LoadBalancerPollingActor extends PollingActor {
  implicit val timeout = Timeout(5 seconds)

  var subnets: List[Subnet] = _

  override def poll() = {
    val vpcsFuture = (getShardCluster(VpcPollingActor.typeName) ? GetVpcs(account, region)).mapTo[List[Vpc]]
    val subnetsFuture = (getShardCluster(SubnetPollingActor.typeName) ? GetSubnets(account, region)).mapTo[List[Subnet]]
    val securityGroupsFuture = (getShardCluster(SecurityGroupPollingActor.typeName) ? GetSecurityGroupIdToNameMappings(account, region)).mapTo[Map[String, SecurityGroupIdentity]]
    val vpcs = Await.result(vpcsFuture, timeout.duration)
    val subnets = Await.result(subnetsFuture, timeout.duration)
    val securityGroupIdToName: Map[String, SecurityGroupIdentity] = Await.result(securityGroupsFuture, timeout.duration)
    val loadBalancers = eddaService.loadBalancers
    loadBalancers.foreach { loadBalancer =>
      var normalizedState = loadBalancer.state.convertToSecurityGroupNames(securityGroupIdToName).
        populateVpcAttributes(vpcs, subnets)
      val reference = AwsReference(AwsLocation(account, region), loadBalancer.identity)
      val latestState = LoadBalancerLatestState(normalizedState)
      resourceCluster(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, latestState, Option(cloudDriver))
    }
  }
}

object LoadBalancerPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[LoadBalancerPollingActor]
}

