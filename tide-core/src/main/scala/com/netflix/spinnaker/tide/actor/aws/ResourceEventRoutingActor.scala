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

import akka.actor.{ActorRef, ActorLogging, Actor}
import akka.contrib.pattern.ClusterSharding
import com.fasterxml.jackson.annotation.{JsonUnwrapped, JsonIgnore}
import com.netflix.frigga.autoscaling.AutoScalingGroupNameBuilder
import com.netflix.spinnaker.tide.actor.PipelineActor
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor._
import com.netflix.spinnaker.tide.api.{Pipeline, PipelineState}
import scala.concurrent.duration.DurationInt

class ResourceEventRoutingActor(private val cloudDriver: CloudDriverActor.Ref, private val front50: Front50Actor.Ref) extends Actor with ActorLogging {

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledSendingReference = scheduler.schedule(0 seconds, 15 seconds,
    getShardCluster(TaskDirector.typeName), AwsResourceReference(self))

  override def postStop(): Unit = scheduledSendingReference.cancel()

  override def receive: Receive = {
    case msg @ AwsResourceProtocol(_, event: SecurityGroupEvent, _) =>
      getShardCluster(SecurityGroupActor.typeName) forward msg.copy(cloudDriver = Option(cloudDriver))

    case msg @ AwsResourceProtocol(_, event: LoadBalancerEvent, _) =>
      getShardCluster(LoadBalancerActor.typeName) forward msg.copy(cloudDriver = Option(cloudDriver))

    case msg @ AwsResourceProtocol(_, event: CloneServerGroup, _) =>
      cloudDriver forward msg

    case msg @ AwsResourceProtocol(_, event: ServerGroupEvent, _) =>
      getShardCluster(ServerGroupActor.typeName) forward msg.copy(cloudDriver = Option(cloudDriver))

    case msg: InsertPipeline =>
      front50 forward msg

    case msg: PipelineEvent =>
      getShardCluster(PipelineActor.typeName) forward msg
  }
}

case class ClusterName(appName: String, stack: String, detail: String) {
  def name: String = {
    val name = new AutoScalingGroupNameBuilder()
    name.setAppName(appName)
    name.setStack(stack)
    name.setDetail(detail)
    name.buildGroupName()
  }
}

sealed trait ResourceEvent extends Serializable
sealed trait SecurityGroupEvent extends ResourceEvent
sealed trait LoadBalancerEvent extends ResourceEvent
sealed trait ServerGroupEvent extends ResourceEvent

object ResourceEventRoutingActor {
  type Ref = ActorRef

  case class LatestStateTimeout() extends ResourceEvent
  case class ClearLatestState() extends ResourceEvent
  case class ClearDesiredState() extends ResourceEvent
  case class MutateState() extends ResourceEvent

  case class AwsResourceReference(awsResource: ResourceEventRoutingActor.Ref) extends ResourceEvent

  case class AwsResourceProtocol[T <: AwsIdentity](awsReference: AwsReference[T], event: ResourceEvent,
                                                   cloudDriver: Option[ActorRef] = None) extends AkkaClustered with ResourceEvent {
    @JsonIgnore val akkaIdentifier = s"${awsReference.akkaIdentifier}"
  }

  case class GetSecurityGroup() extends SecurityGroupEvent
  case class UpsertSecurityGroup(state: SecurityGroupState, overwrite: Boolean = true) extends SecurityGroupEvent
  case class SecurityGroupLatestState(securityGroupId: String, state: SecurityGroupState) extends SecurityGroupEvent
  case class SecurityGroupDetails(awsReference: AwsReference[SecurityGroupIdentity],
                                  latestState: Option[SecurityGroupLatestState],
                                  desiredState: Option[UpsertSecurityGroup]) extends SecurityGroupEvent

  case class GetLoadBalancer() extends LoadBalancerEvent
  case class UpsertLoadBalancer(state: LoadBalancerState, overwrite: Boolean = true) extends LoadBalancerEvent
  case class LoadBalancerLatestState(state: LoadBalancerState) extends LoadBalancerEvent
  case class LoadBalancerDetails(awsReference: AwsReference[LoadBalancerIdentity],
                                 latestState: Option[LoadBalancerLatestState],
                                 desiredState: Option[UpsertLoadBalancer]) extends LoadBalancerEvent

  case class GetServerGroup() extends ServerGroupEvent
  case class CloneServerGroup(autoScalingGroup: AutoScalingGroupState, launchConfiguration: LaunchConfigurationState,
                              startDisabled: Boolean = false, application: Option[String] = None,
                              stack: Option[String] = None, detail: Option[String] = None) extends ServerGroupEvent
  case class ServerGroupLatestState(autoScalingGroup: AutoScalingGroupState,
                                    launchConfiguration: LaunchConfigurationState) extends ServerGroupEvent
  case class ServerGroupDetails(awsReference: AwsReference[ServerGroupIdentity],
                                latestState: Option[ServerGroupLatestState]) extends ServerGroupEvent

  sealed trait PipelineEvent extends ResourceEvent
  case class GetPipeline(id: String) extends PipelineEvent
  case class PipelineDetails(id: String, state: Option[PipelineState]) extends PipelineEvent
  case class InsertPipeline(state: PipelineState) extends PipelineEvent
}

