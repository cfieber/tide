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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.ContractActorImpl
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPollingProtocol
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.{LatestSecurityGroupIdToNameMappings, GetSecurityGroupIdToNameMappings}
import com.netflix.spinnaker.tide.actor.service.EddaActor.RetrieveSecurityGroups
import com.netflix.spinnaker.tide.model.{AwsResourceProtocol, SecurityGroupLatestState, AwsApi}
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupActor

class SecurityGroupPollingActor extends EddaPollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, SecurityGroupPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var securityGroupIdToName: Option[Map[String, SecurityGroupIdentity]] = None

  override def receive: Receive = {
    case msg: GetSecurityGroupIdToNameMappings =>
      if (securityGroupIdToName.isEmpty) {
        handlePoll(msg.location)
      }
      sender() ! LatestSecurityGroupIdToNameMappings(msg.location, securityGroupIdToName.get)
    case msg => super.receive(msg)
  }

  override def handlePoll(location: AwsLocation): Unit = {
    val securityGroups = edda.ask(RetrieveSecurityGroups(location)).resources
    securityGroupIdToName = Some(securityGroups.map { securityGroup =>
      securityGroup.groupId -> securityGroup.identity
    }.toMap)
    securityGroups.foreach { securityGroup =>
      val normalizedState = securityGroup.state.ensureSecurityGroupNameOnIngressRules(securityGroupIdToName.get)
      val latestState = SecurityGroupLatestState(securityGroup.groupId, normalizedState)
      val reference = AwsReference(location, securityGroup.identity)
      clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(reference, latestState)
    }
  }
}

object SecurityGroupPollingActor extends PollingActorObject {
  val props = Props[SecurityGroupPollingActor]

  case class GetSecurityGroupIdToNameMappings(location: AwsLocation) extends EddaPollingProtocol
  case class LatestSecurityGroupIdToNameMappings(location: AwsLocation, map: Map[String, SecurityGroupIdentity]) extends EddaPollingProtocol
}

trait SecurityGroupPollingContract {
  def ask(msg: GetSecurityGroupIdToNameMappings): LatestSecurityGroupIdToNameMappings
}

class SecurityGroupPollingContractActor(val clusterSharding: ClusterSharding) extends SecurityGroupPollingContract
  with ContractActorImpl[EddaPollingProtocol] {
  val actorObject = SecurityGroupPollingActor

  def ask(msg: GetSecurityGroupIdToNameMappings): LatestSecurityGroupIdToNameMappings = {
    askActor(msg, classOf[LatestSecurityGroupIdToNameMappings])
  }
}
