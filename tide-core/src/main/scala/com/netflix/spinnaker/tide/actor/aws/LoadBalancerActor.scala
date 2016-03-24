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
import com.netflix.spinnaker.tide.actor.aws.LoadBalancerActor.{LoadBalancerComparableAttributes, DiffLoadBalancer}
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor.{GetDiff, DiffAttributes}
import com.netflix.spinnaker.tide.actor.service.{CloudDriverActor, ConstructCloudDriverOperations}
import com.netflix.spinnaker.tide.model._
import com.netflix.spinnaker.tide.model.AwsApi._
import scala.beans.BeanProperty
import scala.concurrent.duration.DurationInt

class LoadBalancerActor extends Actor with ActorLogging {

  private implicit val dispatcher = context.dispatcher

  val clusterSharding = ClusterSharding.get(context.system)
  context.setReceiveTimeout(2 minutes)

  var awsReference: AwsReference[LoadBalancerIdentity] = _
  var desiredState: Option[UpsertLoadBalancer] = None
  var latestState: Option[LoadBalancerLatestState] = None

  override def receive: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[LoadBalancerIdentity]], wrapper.event)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[LoadBalancerIdentity],
                                        event: ResourceEvent) = event match {
    case event: GetLoadBalancer =>
      this.awsReference = newAwsReference
      sender() ! new LoadBalancerDetails(newAwsReference, latestState, desiredState)

    case event: ClearLatestState =>
      if (latestState.isDefined) {
        latestState = None
        desiredState = None
        val comparableEvent = DiffLoadBalancer(awsReference, None)
        clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
      }

    case event: UpsertLoadBalancer =>
      this.awsReference = newAwsReference
      if (desiredState != Option(event)) {
        desiredState = Option(event)
        desiredState.foreach(mutate)
      }

    case event: LoadBalancerLatestState =>
      this.awsReference = newAwsReference
      if (latestState != Option(event)) {
        latestState = Option(event)
        desiredState = None
        val comparableEvent = DiffLoadBalancer(awsReference,
          Option(LoadBalancerComparableAttributes.from(event.state)))
        clusterSharding.shardRegion(AttributeDiffActor.typeName) ! comparableEvent
        desiredState.foreach(mutate)
      }
      desiredState.foreach(mutate)
  }

  private def mutate(upsertLoadBalancer: UpsertLoadBalancer) = {
    val cloudDriverActor = clusterSharding.shardRegion(CloudDriverActor.typeName)
    latestState match {
      case None =>
        cloudDriverActor ! AwsResourceProtocol(awsReference, upsertLoadBalancer)
      case Some(latest) if upsertLoadBalancer.overwrite =>
        val latestOp = ConstructCloudDriverOperations.constructUpsertLoadBalancerOperation(awsReference, latest.state)
        val upsertOp = ConstructCloudDriverOperations.constructUpsertLoadBalancerOperation(awsReference, upsertLoadBalancer.state)
        if (latestOp != upsertOp) {
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertLoadBalancer)
        } else {
          desiredState = None
        }
      case Some(latest) =>
        desiredState = None
    }
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

