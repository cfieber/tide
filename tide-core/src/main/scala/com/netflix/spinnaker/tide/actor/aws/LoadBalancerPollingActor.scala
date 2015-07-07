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

import akka.actor.{Props, ActorRef}
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.{AwsResourceProtocol, LoadBalancerLatestState}

class LoadBalancerPollingActor extends PollingActor {

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()
  var subnets: List[Subnet] = _

  override def poll() = {
      val subnets = eddaService.subnets
      val loadBalancers = eddaService.loadBalancers
      eddaService.securityGroups.foreach { securityGroup =>
        securityGroupIdToName += (securityGroup.groupId -> securityGroup.identity)
      }
      loadBalancers.foreach { loadBalancer =>
        val securityGroupNames = loadBalancer.state.securityGroups.map{ securityGroupName =>
          if (securityGroupName.startsWith("sg-")) {
            securityGroupIdToName.get(securityGroupName) match {
              case None => securityGroupName
              case Some(identity) => identity.groupName
            }
          } else {
            securityGroupName
          }
        }
        var normalizedState = loadBalancer.state.copy(securityGroups = securityGroupNames)
        loadBalancer.state.subnets.headOption.foreach { subnetId =>
          val subnetOption = subnets.find(_.subnetId == subnetId)
          subnetOption.foreach { subnet =>
            normalizedState = normalizedState.copy(subnetType = Option(subnet.subnetType), vpcId = Option(subnet.vpcId))
          }
        }
        val latestState = LoadBalancerLatestState(normalizedState)
        resourceCluster(LoadBalancerActor.typeName) ! AwsResourceProtocol(AwsReference(AwsLocation(account, region),
          loadBalancer.identity), latestState, Option(cloudDriver))
      }
  }
}

object LoadBalancerPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[LoadBalancerPollingActor]
}

