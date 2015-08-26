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

import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPoll
import com.netflix.spinnaker.tide.actor.polling.PollingActor.Poll
import com.netflix.spinnaker.tide.actor.service.{EddaContractActorImpl, EddaContract}
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

trait EddaPollingActor extends PollingActor {

  def edda: EddaContract = new EddaContractActorImpl(ClusterSharding.get(context.system))

  override def receive: Receive = {
    case msg: EddaPoll =>
      pollScheduler.scheduleNextPoll(msg)
      handlePoll(msg.location)
    case _ => Nil
  }

  def handlePoll(location: AwsLocation)

}

object EddaPollingActor {
  trait EddaPollingProtocol extends PollingProtocol {
    val location: AwsLocation
    def pollingIdentifier = location.akkaIdentifier
  }
  case class EddaPoll(location: AwsLocation) extends EddaPollingProtocol with Poll
}

