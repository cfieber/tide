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

package com.netflix.spinnaker.tide.actor.sync

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.sync.AwsApi._
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

class SecurityGroupPollingActor extends Actor with ActorLogging {

  var account: String = _
  var region: String = _
  var cloudDriver: CloudDriverActor.Ref = _
  var eddaService: EddaService = _

  def securityGroupCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(SecurityGroupActor.typeName)
  }

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledPolling = scheduler.schedule(0 seconds, 15 seconds, self, Poll())

  override def postStop(): Unit = scheduledPolling.cancel()

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def constructEddaService(event: Start): EddaService = {
    new EddaServiceBuilder().constructEddaService(event)
  }

  override def receive: Receive = {
    case event: Start =>
      account = event.account
      region = event.region
      cloudDriver = event.cloudDriver
      eddaService = constructEddaService(event)

    case event: Poll =>
      eddaService.securityGroups.foreach { securityGroup =>
        securityGroupIdToName += (securityGroup.groupId -> securityGroup.identity)
        val state = addSecurityGroupNameToIngressRules(securityGroup.state)
        val latestState = SecurityGroupLatestState(state)
        securityGroupCluster ! AwsResourceProtocol(AwsReference(AwsLocation(account, region),
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

object SecurityGroupPollingActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[SecurityGroupPollingActor]),
      idExtractor = {
        case msg: Start =>
          (msg.akkaIdentifier, msg)
      },
      shardResolver = {
        case msg: Start =>
          (msg.akkaIdentifier.hashCode % 10).toString
      })
  }
}

