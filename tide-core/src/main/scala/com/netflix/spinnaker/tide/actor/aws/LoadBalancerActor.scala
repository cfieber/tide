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
import com.netflix.spinnaker.tide.actor.aws.LoadBalancerActor.{LoadBalancerComparableAttributes, DiffLoadBalancer}
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor.DiffServerGroup
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{GetDiff, DiffAttributes}
import com.netflix.spinnaker.tide.actor.service.{CloudDriverActor, ConstructCloudDriverOperations}
import com.netflix.spinnaker.tide.model._
import com.netflix.spinnaker.tide.model.AwsApi._
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class LoadBalancerActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(20 seconds, self, LatestStateTimeout)

  val clusterSharding = ClusterSharding.get(context.system)

  var awsReference: AwsReference[LoadBalancerIdentity] = _
  var desiredState: Option[UpsertLoadBalancer] = None
  var latestState: Option[LoadBalancerLatestState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {
    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[LoadBalancerIdentity]],
        wrapper.event.asInstanceOf[LoadBalancerEvent])

    case LatestStateTimeout =>
      if (latestState.isDefined) {
        persist(ClearLatestState()) { it =>
          updateState(it)
          val comparableEvent = DiffLoadBalancer(awsReference, None)
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        }
      } else {
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
      if (desiredState.isDefined) {
        self ! MutateState()
      }

    case event: MutateState =>
      desiredState.foreach(mutate)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[LoadBalancerIdentity],
                                        event: LoadBalancerEvent) = event match {
    case event: GetLoadBalancer =>
      updateReferences(newAwsReference)
      sender() ! new LoadBalancerDetails(newAwsReference, latestState, desiredState)

    case event: UpsertLoadBalancer =>
      updateReferences(newAwsReference)
      if (desiredState != Option(event)) {
        persist(event) { e => updateState(event) }
      }
      self ! MutateState()

    case event: LoadBalancerLatestState =>
      updateReferences(newAwsReference)
      latestStateTimeout.cancel()
      latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)
      if (latestState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          val comparableEvent = DiffLoadBalancer(awsReference,
            Option(LoadBalancerComparableAttributes.from(event.state)))
          clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        }
      }
      self ! MutateState()
  }

  private def mutate(upsertLoadBalancer: UpsertLoadBalancer) = {
    val cloudDriverActor = clusterSharding.shardRegion(CloudDriverActor.typeName)
    latestState match {
      case None =>
        cloudDriverActor ! AwsResourceProtocol(awsReference, upsertLoadBalancer)
      case Some(latest) =>
        val latestOp = ConstructCloudDriverOperations.constructUpsertLoadBalancerOperation(awsReference, latest.state)
        val upsertOp = ConstructCloudDriverOperations.constructUpsertLoadBalancerOperation(awsReference, upsertLoadBalancer.state)
        if (latestOp == upsertOp) {
          persist(ClearDesiredState())(it => updateState(it))
        } else {
          if (upsertLoadBalancer.overwrite) {
            cloudDriverActor ! AwsResourceProtocol(awsReference, upsertLoadBalancer)
          } else {
            persist(ClearDesiredState())(it => updateState(it))
          }
        }
    }
  }

  private def updateReferences(newAwsReference: AwsReference[LoadBalancerIdentity]) = {
    awsReference = newAwsReference
  }

  private def updateState(event: Any) = {
    event match {
      case event: UpsertLoadBalancer =>
        desiredState = Option(event)
      case event: LoadBalancerLatestState =>
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

object LoadBalancerActor extends ClusteredActorObject {
  val props = Props[LoadBalancerActor]

  case class DiffLoadBalancer(identity: AwsReference[LoadBalancerIdentity],
                             attributes: Option[LoadBalancerComparableAttributes])
    extends DiffAttributes[AwsReference[LoadBalancerIdentity]] {
    override def akkaIdentifier: String = {
      s"LoadBalancerDiff.${identity.location.account}.${identity.identity.loadBalancerName}"
    }
  }

  case class GetLoadBalancerDiff(account: String, name: String) extends GetDiff {
    override def akkaIdentifier: String = {
      s"LoadBalancerDiff.$account.$name"
    }
  }

  case class LoadBalancerComparableAttributes(
      @BeanProperty availabilityZones: Set[String],
      @BeanProperty healthCheck: HealthCheck,
      @BeanProperty listenerDescriptions: Set[ListenerDescription],
      @BeanProperty scheme: String,
      @BeanProperty securityGroups: Set[String],
      @BeanProperty sourceSecurityGroup: ElbSourceSecurityGroup,
      @BeanProperty subnets: Set[String],
      @BeanProperty subnetType: Option[String])

  object LoadBalancerComparableAttributes {
    def from(state: LoadBalancerState): LoadBalancerComparableAttributes = {
      LoadBalancerComparableAttributes(
        state.availabilityZones,
        state.healthCheck,
        state.listenerDescriptions,
        state.scheme,
        state.securityGroups,
        state.sourceSecurityGroup,
        state.subnets,
        state.subnetType
      )
    }
  }
}

