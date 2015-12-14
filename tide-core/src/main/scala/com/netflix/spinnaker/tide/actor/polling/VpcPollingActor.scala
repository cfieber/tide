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
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.{AwsPoll, AwsPollingProtocol}
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.{LatestVpcs, GetVpcs}
import com.netflix.spinnaker.tide.model.{AwsConversion, AkkaClustered, AwsApi}
import AwsApi._
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class VpcPollingActor extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _
  var vpcs: Seq[Vpc] = _
  var subnets: Seq[Subnet] = _

  override def receive: Receive = {
    case msg: GetVpcs =>
      if (Option(vpcs).isDefined) {
        sender() ! LatestVpcs(msg.location, vpcs, subnets)
      }

    case msg: AwsPoll =>
      location = msg.location
      val amazonEc2 = getAwsServiceProvider(location).getAmazonEC2

      val subnetsFuture = Future { amazonEc2.describeSubnets().getSubnets.map(AwsConversion.subnetFrom) }
      val vpcsFuture = Future { amazonEc2.describeVpcs().getVpcs }
      val vpcClassicLinkLookupFuture = Future {
        amazonEc2.describeVpcClassicLink().getVpcs.map { vpcClassicLink =>
          vpcClassicLink.getVpcId -> vpcClassicLink.isClassicLinkEnabled.booleanValue()
        }.toMap
      }

      for {
        subnets <- subnetsFuture
        vpcs <- vpcsFuture
        vpcClassicLinkLookup <- vpcClassicLinkLookupFuture
      } {
        val combinedVpcAttributes = vpcs.map { vpc =>
          val classicLinkEnabled: Boolean = vpcClassicLinkLookup.getOrElse(vpc.getVpcId, false)
          AwsConversion.vpcFrom(vpc, classicLinkEnabled)
        }
        val latestVpcs = LatestVpcs(location, combinedVpcAttributes, subnets)
        clusterSharding.shardRegion(LoadBalancerPollingActor.typeName) ! latestVpcs
        clusterSharding.shardRegion(ServerGroupPollingActor.typeName) ! latestVpcs
        clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) ! latestVpcs
      }
  }

}

object VpcPollingActor extends PollingActorObject {
  val props = Props[VpcPollingActor]

  case class GetVpcs(location: AwsLocation) extends AwsPollingProtocol
  case class LatestVpcs(location: AwsLocation, vpcs: Seq[Vpc], subnets: Seq[Subnet]) extends AwsPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }

}
