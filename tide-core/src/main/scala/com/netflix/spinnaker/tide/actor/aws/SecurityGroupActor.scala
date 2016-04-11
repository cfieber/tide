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
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupActor.{SecurityGroupComparableAttributes, DiffSecurityGroup}
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{GetDiff, DiffAttributes}
import com.netflix.spinnaker.tide.actor.service.{CloudDriverActor, ConstructCloudDriverOperations}
import com.netflix.spinnaker.tide.model.CloudDriverService.UpsertSecurityGroupOperation
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class SecurityGroupActor extends Actor with ActorLogging {

  private implicit val dispatcher = context.dispatcher
  context.setReceiveTimeout(2 minutes)

  val clusterSharding = ClusterSharding.get(context.system)

  var awsReference: AwsReference[SecurityGroupIdentity] = _
  var desiredState: Option[UpsertSecurityGroup] = None
  var latestState: Option[SecurityGroupLatestState] = None

  override def receive: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[SecurityGroupIdentity]], wrapper.event)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[SecurityGroupIdentity],
                                        event: ResourceEvent) = event match {

    case event: GetSecurityGroup =>
      this.awsReference = newAwsReference
      sender() ! new SecurityGroupDetails(newAwsReference, latestState, desiredState)

    case event: ClearLatestState =>
      this.awsReference = newAwsReference
      if (latestState.isDefined) {
        latestState = None
        desiredState = None
        val comparableEvent = DiffSecurityGroup(awsReference, None)
        clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
      }

    case event: UpsertSecurityGroup =>
      this.awsReference = newAwsReference
      if (desiredState != Option(event)) {
        desiredState = Option(event)
        desiredState.foreach(mutate)
      }

    case event: SecurityGroupLatestState =>
      this.awsReference = newAwsReference
      if (latestState != Option(event)) {
        latestState = Option(event)
        desiredState = None
        val comparableEvent = DiffSecurityGroup(awsReference,
          Option(SecurityGroupComparableAttributes.from(event.state)))
        clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
      }
      desiredState.foreach(mutate)

  }

  private def mutate(upsertSecurityGroup: UpsertSecurityGroup) = {
    val cloudDriverActor = clusterSharding.shardRegion(CloudDriverActor.typeName)
    latestState match {
      case None =>
        if (upsertSecurityGroup.state.ipPermissions.nonEmpty) {
          val stateWithoutIngress = upsertSecurityGroup.state.copy(ipPermissions = Set())
          val upsertWithoutIngress = upsertSecurityGroup.copy(state = stateWithoutIngress)
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertWithoutIngress)
        }
        cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
      case Some(latest) if upsertSecurityGroup.overwrite || latest.state.ipPermissions.isEmpty =>
        if (isDesiredStateRealized(upsertSecurityGroup, latest)) {
          desiredState = None
        } else {
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
        }
      case Some(latest) =>
        desiredState = None
    }
  }

  def isDesiredStateRealized(upsertSecurityGroup: UpsertSecurityGroup, latest: SecurityGroupLatestState): Boolean = {
    val upsertIngress = AwsConversion.spreadIngress(upsertSecurityGroup.state.ipPermissions)
    val latestIngress = AwsConversion.spreadIngress(latest.state.ipPermissions)
    upsertIngress.subsetOf(latestIngress)
  }
}

object SecurityGroupActor extends ClusteredActorObject {
  val props = Props[SecurityGroupActor]

  case class DiffSecurityGroup(identity: AwsReference[SecurityGroupIdentity],
                             attributes: Option[SecurityGroupComparableAttributes])
    extends DiffAttributes[AwsReference[SecurityGroupIdentity]] {
    override def akkaIdentifier: String = {
      s"SecurityGroupDiff.${identity.location.account}.${identity.identity.groupName}"
    }
  }

  case class GetSecurityGroupDiff(account: String, name: String) extends GetDiff {
    override def akkaIdentifier: String = {
      s"SecurityGroupDiff.$account.$name"
    }
  }

  case class SecurityGroupComparableAttributes(
                                                @BeanProperty description: String,
                                                @BeanProperty ipPermissions: Set[IpPermission])

  object SecurityGroupComparableAttributes {
    def from(state: SecurityGroupState): SecurityGroupComparableAttributes = {
      SecurityGroupComparableAttributes(state.description, state.ipPermissions)
    }
  }
}
