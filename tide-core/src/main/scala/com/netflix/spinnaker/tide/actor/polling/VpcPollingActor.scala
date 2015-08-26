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

package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.ContractActorImpl
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPollingProtocol
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.{LatestVpcs, GetVpcs}
import com.netflix.spinnaker.tide.actor.service.EddaActor.RetrieveVpcs
import com.netflix.spinnaker.tide.model.AwsApi
import AwsApi._


class VpcPollingActor extends EddaPollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, VpcPollingActor)

  var vpcs: Option[List[Vpc]] = None

  override def receive: Receive = {
    case msg: GetVpcs =>
      if (vpcs.isEmpty) {
        handlePoll(msg.location)
      }
      sender() ! LatestVpcs(msg.location, vpcs.get)
    case msg => super.receive(msg)
  }

  override def handlePoll(location: AwsLocation): Unit = {
    vpcs = Some(edda.ask(RetrieveVpcs(location)).resources)
  }

}

object VpcPollingActor extends PollingActorObject {
  val props = Props[VpcPollingActor]

  case class GetVpcs(location: AwsLocation) extends EddaPollingProtocol
  case class LatestVpcs(location: AwsLocation, resources: List[Vpc]) extends EddaPollingProtocol
}

trait VpcPollingContract {
  def ask(msg: GetVpcs): LatestVpcs
}

class VpcPollingContractActor(val clusterSharding: ClusterSharding) extends VpcPollingContract
  with ContractActorImpl[EddaPollingProtocol] {
  val actorObject = VpcPollingActor

  def ask(msg: GetVpcs): LatestVpcs = {
    askActor(msg, classOf[LatestVpcs])
  }
}
