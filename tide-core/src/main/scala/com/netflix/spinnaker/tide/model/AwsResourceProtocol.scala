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

package com.netflix.spinnaker.tide.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.model.Front50Service.PipelineState

case class AwsResourceProtocol[T <: AwsIdentity](awsReference: AwsReference[T], event: ResourceEvent)
  extends AkkaClustered with ResourceEvent {
  @JsonIgnore def akkaIdentifier = s"${awsReference.akkaIdentifier}"
}

sealed trait ResourceEvent extends Serializable
sealed trait SecurityGroupEvent extends ResourceEvent
sealed trait LoadBalancerEvent extends ResourceEvent
sealed trait ServerGroupEvent extends ResourceEvent

case class ClearLatestState() extends ResourceEvent

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

