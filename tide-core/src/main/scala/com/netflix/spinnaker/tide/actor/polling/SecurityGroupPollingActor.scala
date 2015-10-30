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
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPoll, EddaPollingProtocol}
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.{LatestSecurityGroupIdToNameMappings, GetSecurityGroupIdToNameMappings}
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundSecurityGroups, RetrieveSecurityGroups}
import com.netflix.spinnaker.tide.model._
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws.{ClassicLinkInstancesActor, SecurityGroupActor}

class SecurityGroupPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, SecurityGroupPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _
  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = _

  var currentIds: Seq[SecurityGroupIdentity] = Nil

  override def receive: Receive = {
    case msg: GetSecurityGroupIdToNameMappings =>
      if (Option(securityGroupIdToName).isDefined) {
        sender() ! LatestSecurityGroupIdToNameMappings(msg.location, securityGroupIdToName)
      }

    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveSecurityGroups(location)

    case msg: FoundSecurityGroups =>
      val securityGroups = msg.resources

      val oldIds = currentIds
      currentIds = securityGroups.map(_.identity)
      val removedIds = oldIds.toSet -- currentIds.toSet
      removedIds.foreach { identity =>
        val reference = AwsReference(location, identity)
        clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(reference, ClearLatestState())
      }

      securityGroupIdToName = securityGroups.map { securityGroup =>
        securityGroup.groupId -> securityGroup.identity
      }.toMap
      val securityGroupIdToNameMsg = LatestSecurityGroupIdToNameMappings(location, securityGroupIdToName)
      clusterSharding.shardRegion(LoadBalancerPollingActor.typeName) ! securityGroupIdToNameMsg
      clusterSharding.shardRegion(ServerGroupPollingActor.typeName) ! securityGroupIdToNameMsg
      clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) ! securityGroupIdToNameMsg
        securityGroups.foreach { securityGroup =>
        val normalizedState = securityGroup.state.ensureSecurityGroupNameOnIngressRules(securityGroupIdToName)
        val latestState = SecurityGroupLatestState(securityGroup.groupId, normalizedState)
        val reference = AwsReference(location, securityGroup.identity)
        clusterSharding.shardRegion(SecurityGroupActor.typeName) ! AwsResourceProtocol(reference, latestState)
      }

  }

}

object SecurityGroupPollingActor extends PollingActorObject {
  val props = Props[SecurityGroupPollingActor]

  case class GetSecurityGroupIdToNameMappings(location: AwsLocation) extends EddaPollingProtocol
  case class LatestSecurityGroupIdToNameMappings(location: AwsLocation, map: Map[String, SecurityGroupIdentity]) extends EddaPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
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
