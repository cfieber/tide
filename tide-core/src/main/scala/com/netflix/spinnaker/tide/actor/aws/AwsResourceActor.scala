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
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.frigga.autoscaling.AutoScalingGroupNameBuilder
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.DeepCopyActor.CloneServerGroupTask
import scala.concurrent.duration.DurationInt

class AwsResourceActor(private val cloudDriver: CloudDriverActor.Ref) extends Actor with ActorLogging {

  def securityGroupCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(SecurityGroupActor.typeName)
  }

  def loadBalancerCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(LoadBalancerActor.typeName)
  }

  def serverGroupCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(ServerGroupActor.typeName)
  }

  def deepCopyDirector: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(DeepCopyDirector.typeName)
  }

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledSendingReference = scheduler.schedule(0 seconds, 15 seconds, deepCopyDirector, AwsResourceReference(self))

  override def postStop(): Unit = scheduledSendingReference.cancel()

  override def receive: Receive = {
    case msg @ AwsResourceProtocol(_, event: SecurityGroupEvent, _) =>
      securityGroupCluster forward msg.copy(cloudDriver = Option(cloudDriver))

    case msg @ AwsResourceProtocol(_, event: LoadBalancerEvent, _) =>
      loadBalancerCluster forward msg.copy(cloudDriver = Option(cloudDriver))

    case msg @ AwsResourceProtocol(_, event: CloneServerGroup, _) =>
      cloudDriver forward msg

    case msg @ AwsResourceProtocol(_, event: ServerGroupEvent, _) =>
      serverGroupCluster forward msg.copy(cloudDriver = Option(cloudDriver))
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

sealed trait AwsResourceEvent

object AwsResourceActor {
  type Ref = ActorRef

  case class LatestStateTimeout() extends AwsResourceEvent

  case class AwsResourceReference(awsResource: AwsResourceActor.Ref) extends AwsResourceEvent

  case class AwsResourceProtocol[T <: AwsIdentity](awsReference: AwsReference[T], event: AwsResourceEvent,
                                                   cloudDriver: Option[ActorRef] = None) extends AkkaClustered with AwsResourceEvent {
    @JsonIgnore val akkaIdentifier = s"${awsReference.akkaIdentifier}"
  }

  sealed trait SecurityGroupEvent extends AwsResourceEvent
  case class GetSecurityGroup() extends SecurityGroupEvent
  case class UpsertSecurityGroup(state: SecurityGroupState, overwrite: Boolean = true) extends SecurityGroupEvent
  case class SecurityGroupLatestState(state: SecurityGroupState) extends SecurityGroupEvent
  case class SecurityGroupDetails(awsReference: AwsReference[SecurityGroupIdentity],
                                  latestState: Option[SecurityGroupLatestState],
                                  desiredState: Option[UpsertSecurityGroup]) extends SecurityGroupEvent

  sealed trait LoadBalancerEvent extends AwsResourceEvent
  case class GetLoadBalancer() extends LoadBalancerEvent
  case class UpsertLoadBalancer(state: LoadBalancerState, overwrite: Boolean = true) extends LoadBalancerEvent
  case class LoadBalancerLatestState(state: LoadBalancerState) extends LoadBalancerEvent
  case class LoadBalancerDetails(awsReference: AwsReference[LoadBalancerIdentity],
                                 latestState: Option[LoadBalancerLatestState],
                                 desiredState: Option[UpsertLoadBalancer]) extends LoadBalancerEvent

  sealed trait ServerGroupEvent extends AwsResourceEvent
  case class GetServerGroup() extends ServerGroupEvent
  case class CloneServerGroup(autoScalingGroup: AutoScalingGroupState, launchConfiguration: LaunchConfigurationState,
                              startDisabled: Boolean = false, application: Option[String] = None,
                              stack: Option[String] = None, detail: Option[String] = None) extends ServerGroupEvent
  case class ServerGroupLatestState(autoScalingGroup: AutoScalingGroupState,
                                    launchConfiguration: LaunchConfigurationState) extends ServerGroupEvent
  case class ServerGroupDetails(awsReference: AwsReference[ServerGroupIdentity],
                                latestState: Option[ServerGroupLatestState]) extends ServerGroupEvent
}

