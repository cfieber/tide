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
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupActor.{SecurityGroupComparableAttributes, DiffSecurityGroup}
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{GetDiff, DiffAttributes}
import com.netflix.spinnaker.tide.actor.service.{CloudDriverActor, ConstructCloudDriverOperations}
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class SecurityGroupActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  private implicit val dispatcher = context.dispatcher
  context.setReceiveTimeout(5 minutes)

  val clusterSharding = ClusterSharding.get(context.system)

  var awsReference: AwsReference[SecurityGroupIdentity] = _
  var desiredState: Option[UpsertSecurityGroup] = None
  var latestState: Option[SecurityGroupLatestState] = None

  override def receiveCommand: Receive = {
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
        persist(event) { it =>
          updateState(it)
          val comparableEvent = DiffSecurityGroup(awsReference, None)
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        }
      }

    case event: UpsertSecurityGroup =>
      this.awsReference = newAwsReference
      if (desiredState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          desiredState.foreach(mutate)
        }
      }

    case event: SecurityGroupLatestState =>
      this.awsReference = newAwsReference
      if (latestState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          val comparableEvent = DiffSecurityGroup(awsReference,
            Option(SecurityGroupComparableAttributes.from(event.state)))
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
          desiredState.foreach(mutate)
        }
      } else {
        desiredState.foreach(mutate)
      }
  }

  private def mutate(upsertSecurityGroup: UpsertSecurityGroup) = {
    val cloudDriverActor = clusterSharding.shardRegion(CloudDriverActor.typeName)
    latestState match {
      case None =>
        val stateWithoutIngress = upsertSecurityGroup.state.copy(ipPermissions = Set())
        val eventWithoutIngress = upsertSecurityGroup.copy(state = stateWithoutIngress)
        cloudDriverActor ! AwsResourceProtocol(awsReference, eventWithoutIngress)
      case Some(latest) if upsertSecurityGroup.overwrite || latest.state.ipPermissions.isEmpty =>
        val latestOp = ConstructCloudDriverOperations.constructUpsertSecurityGroupOperation(awsReference, latest.state)
        val upsertOp = ConstructCloudDriverOperations.constructUpsertSecurityGroupOperation(awsReference, upsertSecurityGroup.state)
        if (latestOp != upsertOp) {
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
        } else {
          desiredState = None
        }
      case Some(latest) =>
        desiredState = None
    }
  }

  private def updateState(event: Any) = {
    event match {
      case event: UpsertSecurityGroup =>
        desiredState = Option(event)
      case event: SecurityGroupLatestState =>
        latestState = Option(event)
      case event: ClearLatestState =>
        latestState = None
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted => Nil
    case event: Any =>
      updateState(event)
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
