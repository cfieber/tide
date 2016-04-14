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
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.{AwsPoll, AwsPollingProtocol}
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.{LatestSecurityGroupIdToNameMappings, GetSecurityGroupIdToNameMappings}
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model._
import AwsApi._
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupActor
import scala.collection.JavaConversions._

class SecurityGroupPollingActor extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var latestVpcs: Option[LatestVpcs] = None

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = _

  var currentIds: Seq[SecurityGroupIdentity] = Nil

  override def receive: Receive = {
    case msg: GetSecurityGroupIdToNameMappings =>
      if (Option(securityGroupIdToName).isDefined) {
        sender() ! LatestSecurityGroupIdToNameMappings(msg.location, securityGroupIdToName)
      }

    case msg: LatestVpcs =>
      latestVpcs = Option(msg)

    case msg: AwsPoll =>
      val location = msg.location
      latestVpcs match {
        case (Some(LatestVpcs(_, vpcs, subnets))) =>
          val amazonEc2 = getAwsServiceProvider(location).getAmazonEC2
          val securityGroupsOption: Option[Seq[SecurityGroup]] = try {
            Option(amazonEc2.describeSecurityGroups.getSecurityGroups.map(AwsConversion.securityGroupFrom(_, msg.accountMetaData, vpcs)))
          } catch {
            case e: Exception =>
              log.error(e, "failed call to retrieve AWS resources")
              None
          }

          securityGroupsOption.foreach { securityGroups =>
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
        case _ =>
      }
  }

}

object SecurityGroupPollingActor extends PollingActorObject {
  val props = Props[SecurityGroupPollingActor]

  case class GetSecurityGroupIdToNameMappings(location: AwsLocation) extends AwsPollingProtocol
  case class LatestSecurityGroupIdToNameMappings(location: AwsLocation, map: Map[String, SecurityGroupIdentity]) extends AwsPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
}

trait SecurityGroupPollingContract {
  def ask(msg: GetSecurityGroupIdToNameMappings): LatestSecurityGroupIdToNameMappings
}

class SecurityGroupPollingContractActor(val clusterSharding: ClusterSharding) extends SecurityGroupPollingContract
  with ContractActorImpl[AwsPollingProtocol] {
  val actorObject = SecurityGroupPollingActor

  def ask(msg: GetSecurityGroupIdToNameMappings): LatestSecurityGroupIdToNameMappings = {
    askActor(msg, classOf[LatestSecurityGroupIdToNameMappings])
  }
}
