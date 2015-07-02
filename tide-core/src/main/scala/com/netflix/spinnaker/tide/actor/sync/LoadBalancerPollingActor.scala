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

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.sync.AwsApi._
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

class LoadBalancerPollingActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var account: String = _
  var region: String = _
  var eddaUrlTemplate: String = _
  var cloudDriver: CloudDriverActor.Ref = _
  var eddaService: EddaService = _

  def loadBalancerCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(LoadBalancerActor.typeName)
  }

  var securityGroupIdToName: Map[String, SecurityGroupIdentity] = Map()

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledPolling = scheduler.schedule(0 seconds, 15 seconds, self, Poll())

  var subnets: List[Subnet] = _

  override def postStop(): Unit = scheduledPolling.cancel()

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def constructEddaService(): EddaService = {
    new EddaServiceBuilder().constructEddaService(account, region, eddaUrlTemplate)
  }

  override def receiveCommand: Receive = {
    case event: Start =>
      persist(event) { it =>
        updateState(it)
        eddaService = constructEddaService()
      }

    case event: Poll =>
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
        loadBalancerCluster ! AwsResourceProtocol(AwsReference(AwsLocation(account, region), loadBalancer.identity),
          latestState, Option(cloudDriver))
      }
  }

  private def updateState(event: Start) = {
    event match {
      case event: Start =>
        account = event.account
        region = event.region
        eddaUrlTemplate = event.eddaUrlTemplate
        cloudDriver = event.cloudDriver
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      eddaService = constructEddaService()
    case event: Start =>
      updateState(event)
  }

}

object LoadBalancerPollingActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[LoadBalancerPollingActor]),
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

