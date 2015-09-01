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
import com.netflix.spinnaker.tide.actor.service.{CloudDriverActor, ConstructCloudDriverOperations}
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.concurrent.duration.DurationInt

class SecurityGroupActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(20 seconds, self, LatestStateTimeout)

  val clusterSharding = ClusterSharding.get(context.system)

  var awsReference: AwsReference[SecurityGroupIdentity] = _
  var desiredState: Option[UpsertSecurityGroup] = None
  var latestState: Option[SecurityGroupLatestState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {
    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[SecurityGroupIdentity]],
        wrapper.event.asInstanceOf[SecurityGroupEvent])

    case LatestStateTimeout =>
      if (latestState.isDefined) {
        persist(ClearLatestState()) { it => updateState(it) }
      } else {
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
      if (desiredState.isDefined) {
        self ! MutateState()
      }

    case event: MutateState =>
      desiredState.foreach(mutate)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[SecurityGroupIdentity],
                                        event: SecurityGroupEvent) = event match {

    case event: GetSecurityGroup =>
      updateReferences(newAwsReference)
      sender() ! new SecurityGroupDetails(newAwsReference, latestState, desiredState)

    case event: UpsertSecurityGroup =>
      updateReferences(newAwsReference)
      if (desiredState != Option(event)) {
        persist(event) { e => updateState(event) }
      }
      self ! MutateState()

    case event: SecurityGroupLatestState =>
      updateReferences(newAwsReference)
      latestStateTimeout.cancel()
      latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)
      if (latestState != Option(event)) {
        persist(event) { e => updateState(event) }
      }
      self ! MutateState()
  }

  private def mutate(upsertSecurityGroup: UpsertSecurityGroup) = {
    val cloudDriverActor = clusterSharding.shardRegion(CloudDriverActor.typeName)
    latestState match {
      case None =>
        val stateWithoutIngress = upsertSecurityGroup.state.copy(ipPermissions = Set())
        val eventWithoutIngress = upsertSecurityGroup.copy(state = stateWithoutIngress)
        cloudDriverActor ! AwsResourceProtocol(awsReference, eventWithoutIngress)
        cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
      case Some(latest) =>
        val latestOp = ConstructCloudDriverOperations.constructUpsertSecurityGroupOperation(awsReference, latest.state)
        val upsertOp = ConstructCloudDriverOperations.constructUpsertSecurityGroupOperation(awsReference, upsertSecurityGroup.state)
        if (latestOp == upsertOp) {
          persist(ClearDesiredState())(it => updateState(it))
        } else {
          if (upsertSecurityGroup.overwrite || latest.state.ipPermissions.isEmpty) {
            cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
          } else {
            persist(ClearDesiredState())(it => updateState(it))
          }
        }
    }
  }

  private def updateReferences(awsReference: AwsReference[SecurityGroupIdentity]) = {
    this.awsReference = awsReference
  }

  private def updateState(event: Any) = {
    event match {
      case event: UpsertSecurityGroup =>
        desiredState = Option(event)
      case event: SecurityGroupLatestState =>
        latestState = Option(event)
      case event: ClearLatestState =>
        latestState = None
      case event: ClearDesiredState =>
        desiredState = None
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
}
