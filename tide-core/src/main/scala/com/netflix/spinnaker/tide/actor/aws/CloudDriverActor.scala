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
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor._
import com.netflix.spinnaker.tide.actor.aws.CloudDriverActor.{GetTaskDetail, CloudDriverResponse}
import com.netflix.spinnaker.tide.api.CloudDriverService.TaskDetail
import com.netflix.spinnaker.tide.api._

class CloudDriverActor(private val cloudDriverService: CloudDriverService)
  extends Actor with ActorLogging {
  override def receive: Receive = {
    case AwsResourceProtocol(awsReference, event: UpsertSecurityGroup, _) if awsReference.identity.isInstanceOf[SecurityGroupIdentity] =>
      val op = UpsertSecurityGroupOperation.from(awsReference.asInstanceOf[AwsReference[SecurityGroupIdentity]], event.state)
      val taskResult = cloudDriverService.submitTask(op.content())
      sender() ! CloudDriverResponse(cloudDriverService.getTaskDetail(taskResult.id), self)

    case AwsResourceProtocol(awsReference, event: UpsertLoadBalancer, _) if awsReference.identity.isInstanceOf[LoadBalancerIdentity] =>
      val op = UpsertLoadBalancerOperation.from(awsReference.asInstanceOf[AwsReference[LoadBalancerIdentity]], event.state)
      val taskResult = cloudDriverService.submitTask(op.content())
      sender() ! CloudDriverResponse(cloudDriverService.getTaskDetail(taskResult.id), self)

    case AwsResourceProtocol(awsReference, event: CloneServerGroup, _) if awsReference.identity.isInstanceOf[ServerGroupIdentity] =>
      val op = CloneServerGroupOperation.from(awsReference.asInstanceOf[AwsReference[ServerGroupIdentity]], event)
      val taskResult = cloudDriverService.submitTask(op.content())
      sender() ! CloudDriverResponse(cloudDriverService.getTaskDetail(taskResult.id), self)

    case event: GetTaskDetail =>
      sender() ! CloudDriverResponse(cloudDriverService.getTaskDetail(event.id), self)
  }
}

sealed trait CloudDriverProtocol extends Serializable

object CloudDriverActor {
  type Ref = ActorRef

  case class CloudDriverResponse(taskDetail: TaskDetail, cloudDriverReference: CloudDriverActor.Ref) extends CloudDriverProtocol
  case class GetTaskDetail(id: String) extends CloudDriverProtocol

}
