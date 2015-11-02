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
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPoll, EddaPollingProtocol}
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.{LatestVpcs, GetVpcs}
import com.netflix.spinnaker.tide.actor.service.EddaActor
import com.netflix.spinnaker.tide.actor.service.EddaActor.{FoundVpcs, RetrieveSecurityGroups, RetrieveVpcs}
import com.netflix.spinnaker.tide.model.{AkkaClustered, AwsApi}
import AwsApi._

class VpcPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, VpcPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var location: AwsLocation = _
  var vpcs: Seq[Vpc] = _

  override def receive: Receive = {
    case msg: GetVpcs =>
      if (Option(vpcs).isDefined) {
        sender() ! LatestVpcs(msg.location, vpcs)
      }

    case msg: EddaPoll =>
      location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      clusterSharding.shardRegion(EddaActor.typeName) ! RetrieveVpcs(location)

    case msg: FoundVpcs =>
      vpcs = msg.resources
      val latestVpcs = LatestVpcs(location, vpcs)
      clusterSharding.shardRegion(LoadBalancerPollingActor.typeName) ! latestVpcs
      clusterSharding.shardRegion(ServerGroupPollingActor.typeName) ! latestVpcs
      clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) ! latestVpcs
  }

}

object VpcPollingActor extends PollingActorObject {
  val props = Props[VpcPollingActor]

  case class GetVpcs(location: AwsLocation) extends EddaPollingProtocol
  case class LatestVpcs(location: AwsLocation, resources: Seq[Vpc]) extends EddaPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
}
