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
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.{ClusteredActorObject, ContractActorImpl}
import com.netflix.spinnaker.tide.model.AwsApi
import AwsApi._
import com.netflix.spinnaker.tide.actor.service.EddaActor.{RetrieveSecurityGroups, RetrieveLoadBalancers, RetrieveLaunchConfigurations, RetrieveAutoScalingGroups, RetrieveSubnets, RetrieveVpcs, FoundSecurityGroups, FoundLoadBalancers, FoundLaunchConfigurations, FoundAutoScalingGroups, FoundSubnets, FoundVpcs}
import com.netflix.spinnaker.tide.model.EddaService

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
      sender ! FoundVpcs(service.vpcs)
  }
}

sealed trait EddaProtocol extends Serializable

sealed trait EddaProtocolInput extends EddaProtocol {
  val location: AwsLocation
}

object EddaActor extends ClusteredActorObject {
  val props = Props[EddaActor]

  override def idExtractor = {
    case msg: EddaProtocolInput =>
      (msg.location.akkaIdentifier, msg)
  }
  override def shardResolver = {
    case msg: EddaProtocolInput =>
      (msg.location.akkaIdentifier.hashCode % 10).toString
  }

  case class EddaInit(location: AwsLocation, eddaUrlTemplate: String)
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

  case class FoundSecurityGroups(resources: List[SecurityGroup]) extends EddaProtocol

  case class FoundLoadBalancers(resources: List[LoadBalancer]) extends EddaProtocol

  case class FoundLaunchConfigurations(resources: List[LaunchConfiguration]) extends EddaProtocol

  case class FoundAutoScalingGroups(resources: List[AutoScalingGroup]) extends EddaProtocol

  case class FoundSubnets(resources: List[Subnet]) extends EddaProtocol

  case class FoundVpcs(resources: List[Vpc]) extends EddaProtocol

}

trait EddaContract {
  def ask(retrieve: RetrieveSecurityGroups): FoundSecurityGroups
  def ask(retrieve: RetrieveLoadBalancers): FoundLoadBalancers
  def ask(retrieve: RetrieveLaunchConfigurations): FoundLaunchConfigurations
  def ask(retrieve: RetrieveAutoScalingGroups): FoundAutoScalingGroups
  def ask(retrieve: RetrieveSubnets): FoundSubnets
  def ask(retrieve: RetrieveVpcs): FoundVpcs
}

class EddaContractActorImpl(val clusterSharding: ClusterSharding) extends EddaContract with ContractActorImpl[EddaProtocol] {
  override val actorObject = EddaActor

  def ask(retrieve: RetrieveSecurityGroups): FoundSecurityGroups = {
    askActor(retrieve, classOf[FoundSecurityGroups])
  }
  def ask(retrieve: RetrieveLoadBalancers): FoundLoadBalancers = {
    askActor(retrieve, classOf[FoundLoadBalancers])
  }
  def ask(retrieve: RetrieveLaunchConfigurations): FoundLaunchConfigurations = {
    askActor(retrieve, classOf[FoundLaunchConfigurations])
  }
  def ask(retrieve: RetrieveAutoScalingGroups): FoundAutoScalingGroups = {
    askActor(retrieve, classOf[FoundAutoScalingGroups])
  }
  def ask(retrieve: RetrieveSubnets): FoundSubnets = {
    askActor(retrieve, classOf[FoundSubnets])
  }
  def ask(retrieve: RetrieveVpcs): FoundVpcs = {
    askActor(retrieve, classOf[FoundVpcs])
  }

}