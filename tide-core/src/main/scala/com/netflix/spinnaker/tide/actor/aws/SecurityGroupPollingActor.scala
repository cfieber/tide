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
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, SecurityGroupLatestState}
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

class SecurityGroupPollingActor extends PollingActor {

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()

  override def poll() = {
      eddaService.securityGroups.foreach { securityGroup =>
        securityGroupIdToName += (securityGroup.groupId -> securityGroup.identity)
        val state = addSecurityGroupNameToIngressRules(securityGroup.state)
        val latestState = SecurityGroupLatestState(state)
        resourceCluster(SecurityGroupActor.typeName) ! AwsResourceProtocol(AwsReference(AwsLocation(account, region),
          securityGroup.identity), latestState, Option(cloudDriver))
      }
  }

  def addSecurityGroupNameToIngressRules(state: SecurityGroupState): SecurityGroupState = {
    val newIpPermissions = state.ipPermissions.map { ipPermission =>
      val newUserIdGroupPairs = ipPermission.userIdGroupPairs.map {
        case pair@UserIdGroupPairs(_, Some(groupName)) => pair
        case pair@UserIdGroupPairs(Some(groupId), None) =>
          UserIdGroupPairs(Option(groupId), securityGroupIdToName.get(groupId).map(_.groupName))
      }
      ipPermission.copy(userIdGroupPairs = newUserIdGroupPairs)
    }
    state.copy(ipPermissions = newIpPermissions)
  }
}

object SecurityGroupPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[SecurityGroupPollingActor]
}

