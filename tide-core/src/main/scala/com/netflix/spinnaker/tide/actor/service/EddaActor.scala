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

package com.netflix.spinnaker.tide.actor.service

import akka.actor.Props
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.service.EddaActor.{RetrieveSecurityGroups, RetrieveLoadBalancers, RetrieveLaunchConfigurations, RetrieveAutoScalingGroups, RetrieveSubnets, RetrieveVpcs, RetrieveClassicLinkInstanceIds, FoundSecurityGroups, FoundLoadBalancers, FoundLaunchConfigurations, FoundAutoScalingGroups, FoundSubnets, FoundVpcs, FoundClassicLinkInstanceIds}
import com.netflix.spinnaker.tide.model.{AkkaClustered, AwsApi, EddaService}
import AwsApi._

class EddaActor extends RetrofitServiceActor[EddaService] {

  override def operational: Receive = {
    case msg: RetrieveSecurityGroups =>
      sender ! FoundSecurityGroups(service.securityGroups)
    case msg: RetrieveLoadBalancers =>
      sender ! FoundLoadBalancers(service.loadBalancers)
    case msg: RetrieveLaunchConfigurations =>
      sender ! FoundLaunchConfigurations(service.launchConfigurations)
    case msg: RetrieveAutoScalingGroups =>
      sender ! FoundAutoScalingGroups(service.autoScalingGroups)
    case msg: RetrieveSubnets =>
      sender ! FoundSubnets(service.subnets)

    case msg: RetrieveVpcs =>
      val vpcs = service.vpcs
      val vpcClassicLinkLookup: Map[String, Boolean] = service.vpcClassicLinks.map{ vpcClassicLink =>
        vpcClassicLink.vpcId -> vpcClassicLink.classicLinkEnabled
      }.toMap
      val combinedVpcAttributes = vpcs.map { vpc =>
        vpc.copy(classicLinkEnabled = vpcClassicLinkLookup.getOrElse(vpc.vpcId, false))
      }
      sender ! FoundVpcs(combinedVpcAttributes)

    case msg: RetrieveClassicLinkInstanceIds =>
      val classicLinkInstanceIds = service.classicLinkInstanceIds
      sender ! FoundClassicLinkInstanceIds(classicLinkInstanceIds)
  }
}

sealed trait EddaProtocol extends Serializable

sealed trait EddaProtocolInput extends EddaProtocol with AkkaClustered {
  val location: AwsLocation
  val resourceType: Class[_] = this.getClass
  override def akkaIdentifier = s"${location.akkaIdentifier}.${resourceType.getCanonicalName}"
}

object EddaActor extends ClusteredActorObject {
  val props = Props[EddaActor]

  case class EddaInit(location: AwsLocation, eddaUrlTemplate: String, override val resourceType: Class[_])
    extends EddaProtocolInput with RetrofitServiceInit[EddaService] {
    override val url: String = eddaUrlTemplate
      .replaceAll("%account", location.account)
      .replaceAll("%region", location.region)
    override val serviceType: Class[EddaService] = classOf[EddaService]
  }

  case class RetrieveSecurityGroups(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveLoadBalancers(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveLaunchConfigurations(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveAutoScalingGroups(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveSubnets(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveVpcs(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveClassicLinkInstanceIds(location: AwsLocation) extends EddaProtocolInput

  case class FoundSecurityGroups(resources: Seq[SecurityGroup]) extends EddaProtocol
  case class FoundLoadBalancers(resources: Seq[LoadBalancer]) extends EddaProtocol
  case class FoundLaunchConfigurations(resources: Seq[LaunchConfiguration]) extends EddaProtocol
  case class FoundAutoScalingGroups(resources: Seq[AutoScalingGroup]) extends EddaProtocol
  case class FoundSubnets(resources: Seq[Subnet]) extends EddaProtocol
  case class FoundVpcs(resources: Seq[Vpc]) extends EddaProtocol
  case class FoundClassicLinkInstanceIds(resources: Seq[String]) extends EddaProtocol

}
