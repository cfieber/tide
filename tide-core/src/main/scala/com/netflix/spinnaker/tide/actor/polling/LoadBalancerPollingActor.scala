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
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.AwsPoll
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model._
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws._
import scala.collection.JavaConversions._

class LoadBalancerPollingActor() extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var latestSecurityGroups: Option[LatestSecurityGroupIdToNameMappings] = None
  var latestVpcs: Option[LatestVpcs] = None

  var currentIds: Seq[LoadBalancerIdentity] = Nil

  override def receive: Receive = {
    case msg: LatestSecurityGroupIdToNameMappings =>
      latestSecurityGroups = Option(msg)

    case msg: LatestVpcs =>
      latestVpcs = Option(msg)

    case msg: AwsPoll =>
      val location = msg.location
      (latestSecurityGroups, latestVpcs) match {
        case (Some(LatestSecurityGroupIdToNameMappings(_, securityGroupIdToName)), Some(LatestVpcs(_, vpcs, subnets))) =>
          val loadBalancing = getAwsServiceProvider(location).getAmazonElasticLoadBalancing
          val loadBalancers = retrieveAll{ nextToken =>
            val result = loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest().withMarker(nextToken))
            (result.getLoadBalancerDescriptions.map(AwsConversion.loadBalancerFrom), Option(result.getNextMarker))
          }

          val oldIds = currentIds
          currentIds = loadBalancers.map(_.identity)
          val removedIds = oldIds.toSet -- currentIds.toSet
          removedIds.foreach { identity =>
            val reference = AwsReference(location, identity)
            clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, ClearLatestState())
          }

          loadBalancers.foreach { loadBalancer =>
            var normalizedState = loadBalancer.state.convertToSecurityGroupNames(securityGroupIdToName).
              populateVpcAttributes(vpcs, subnets)
            val reference = AwsReference(location, loadBalancer.identity)
            val latestState = LoadBalancerLatestState(normalizedState)
            clusterSharding.shardRegion(LoadBalancerActor.typeName) ! AwsResourceProtocol(reference, latestState)
          }
        case _ =>
      }
  }
}

object LoadBalancerPollingActor extends PollingActorObject {
  val props = Props[LoadBalancerPollingActor]
}

