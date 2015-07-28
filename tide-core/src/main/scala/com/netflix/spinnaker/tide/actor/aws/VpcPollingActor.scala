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

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.PollingActor.PollingClustered
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

class VpcPollingActor extends PollingActor {

  var vpcs: List[Vpc] = _

  override def receiveCommand: Receive = {
    case event: GetVpcs =>
      sender() ! vpcs
    case event =>
      super.receiveCommand(event)
  }

  override def poll() = {
    vpcs = eddaService.vpcs
  }
}

object VpcPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[VpcPollingActor]

  case class GetVpcs(account: String, region: String) extends PollingClustered {
    @JsonIgnore override val pollingIdentifier = s"$account.$region"
  }

}



