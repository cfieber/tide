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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.PersistentActor
import com.netflix.frigga.Names
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor.{DiffServerGroup, ServerGroupComparableAttributes}
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{GetDiff, DiffAttributes}
import com.netflix.spinnaker.tide.actor.comparison.{AttributeDiffActor, AttributeDiffProtocol}
import com.netflix.spinnaker.tide.model._
import AwsApi.{AwsReference, ServerGroupIdentity}
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class ServerGroupActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  val clusterSharding = ClusterSharding.get(context.system)

  private implicit val dispatcher = context.dispatcher
  context.setReceiveTimeout(5 minutes)

  var awsReference: AwsReference[ServerGroupIdentity] = _
  var latestState: Option[ServerGroupLatestState] = None

  override def receiveCommand: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

    case wrapper: AwsResourceProtocol[_] =>
      val reference = wrapper.awsReference.asInstanceOf[AwsReference[ServerGroupIdentity]]
      handleAwsResourceProtocol(reference, wrapper.event)

  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[ServerGroupIdentity],
                                        event: ResourceEvent) = event match {
    case event: GetServerGroup =>
      this.awsReference = newAwsReference
      sender() ! new ServerGroupDetails(newAwsReference, latestState)

    case event: ClearLatestState =>
      this.awsReference = newAwsReference
      if (latestState.isDefined) {
        persist(event) { it =>
          updateState(it)
          val comparableEvent = DiffServerGroup(awsReference, None)
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        }
      }

    case event: ServerGroupLatestState =>
      this.awsReference = newAwsReference
      if (latestState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          val comparableEvent = DiffServerGroup(awsReference,
            Option(ServerGroupComparableAttributes.from(event)))
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        }
      }
  }

  private def updateState(event: ResourceEvent) = {
    event match {
      case event: ServerGroupLatestState =>
        latestState = Option(event)
      case event: ClearLatestState =>
        latestState = None
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case event: ServerGroupEvent =>
      updateState(event)
  }

}

object ServerGroupActor extends ClusteredActorObject {
  val props = Props[ServerGroupActor]

  case class DiffServerGroup(identity: AwsReference[ServerGroupIdentity],
                                        attributes: Option[ServerGroupComparableAttributes])
    extends DiffAttributes[AwsReference[ServerGroupIdentity]] {
    override def akkaIdentifier: String = {
      val clusterName = Names.parseName(identity.identity.autoScalingGroupName).getCluster
      s"ServerGroupDiff.${identity.location.account}.$clusterName"
    }
  }

  case class GetServerGroupDiff(account: String, clusterName: String) extends GetDiff {
    override def akkaIdentifier: String = {
      s"ServerGroupDiff.$account.$clusterName"
    }
  }

  case class ServerGroupComparableAttributes(
      @BeanProperty securityGroups: Set[String],
      @BeanProperty loadBalancerNames: Set[String],
      @BeanProperty availabilityZones: Set[String],
      @BeanProperty imageId: String,
      @BeanProperty suspendedProcesses: Set[String],
      @BeanProperty subnetType: Option[String],
      @BeanProperty vpcName: Option[String],
      @BeanProperty VPCZoneIdentifier: String,
      @BeanProperty instanceType: String,
      @BeanProperty desiredCapacity: Int,
      @BeanProperty maxSize: Int,
      @BeanProperty minSize: Int,
      @BeanProperty defaultCooldown: Int,
      @BeanProperty healthCheckGracePeriod: Int,
      @BeanProperty healthCheckType: String,
      @BeanProperty terminationPolicies: Set[String],
      @BeanProperty associatePublicIpAddress: Option[Boolean],
      @BeanProperty ebsOptimized: Boolean,
      @BeanProperty iamInstanceProfile: String,
      @BeanProperty instanceMonitoring: Boolean,
      @BeanProperty kernelId: String,
      @BeanProperty keyName: String,
      @BeanProperty ramdiskId: String,
      @BeanProperty spotPrice: Option[String])

  object ServerGroupComparableAttributes {
    def from(serverGroupLatestState: ServerGroupLatestState): ServerGroupComparableAttributes = {
      val asg = serverGroupLatestState.autoScalingGroup
      val lc = serverGroupLatestState.launchConfiguration
      ServerGroupComparableAttributes(
        lc.securityGroups,
        asg.loadBalancerNames,
        asg.availabilityZones,
        lc.imageId,
        asg.suspendedProcesses.map(_.processName),
        asg.subnetType,
        asg.vpcName,
        asg.VPCZoneIdentifier,
        lc.instanceType,
        asg.desiredCapacity,
        asg.maxSize,
        asg.minSize,
        asg.defaultCooldown,
        asg.healthCheckGracePeriod,
        asg.healthCheckType,
        asg.terminationPolicies,
        lc.associatePublicIpAddress,
        lc.ebsOptimized,
        lc.iamInstanceProfile,
        lc.instanceMonitoring.enabled,
        lc.kernelId,
        lc.keyName,
        lc.ramdiskId,
        lc.spotPrice)
    }
  }
}


