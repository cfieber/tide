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
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, SecurityGroupLatestState}
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupPollingActor.GetSecurityGroupIdToNameMappings

class SecurityGroupPollingActor extends PollingActor {

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()

  override def receiveCommand: Receive = {
    case event: GetSecurityGroupIdToNameMappings =>
      sender() ! securityGroupIdToName
    case event =>
      super.receiveCommand(event)
  }

  override def poll() = {
    val securityGroups = eddaService.securityGroups
    securityGroupIdToName = securityGroups.map { securityGroup =>
      securityGroup.groupId -> securityGroup.identity
    }.toMap

    securityGroups.foreach { securityGroup =>
      val normalizedState = securityGroup.state.ensureSecurityGroupNameOnIngressRules(securityGroupIdToName)
      val latestState = SecurityGroupLatestState(securityGroup.groupId, normalizedState)
      val reference = AwsReference(AwsLocation(account, region), securityGroup.identity)
      resourceCluster(SecurityGroupActor.typeName) ! AwsResourceProtocol(reference, latestState, Option(cloudDriver))
    }
  }
}

object SecurityGroupPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[SecurityGroupPollingActor]

  case class GetSecurityGroupIdToNameMappings(account: String, region: String) extends AkkaClustered {
    @JsonIgnore val akkaIdentifier = s"$account.$region"
  }
}

