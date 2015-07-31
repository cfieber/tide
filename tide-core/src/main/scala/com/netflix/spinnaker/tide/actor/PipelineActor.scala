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

package com.netflix.spinnaker.tide.actor

import akka.actor.{Props, PoisonPill, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{SecurityGroupIdentity, AwsReference}
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor._
import com.netflix.spinnaker.tide.actor.aws.SecurityGroupEvent
import com.netflix.spinnaker.tide.api.{Pipeline, PipelineState, UpsertSecurityGroupOperation}
import scala.concurrent.duration.DurationInt

class PipelineActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(20 seconds, self, LatestStateTimeout)

  var latestState: Option[PipelineState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {

    case LatestStateTimeout =>
      if (latestState.isDefined) {
        persist(ClearLatestState()) { it => updateState(it) }
      } else {
        context.parent ! Passivate(stopMessage = PoisonPill)
      }

    case event: GetPipeline =>
      sender() ! PipelineDetails(event.id, latestState)

    case event: PipelineDetails =>
      latestStateTimeout.cancel()
      latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)
      if (latestState != event.state) {
        persist(event) { e => updateState(event) }
      }
  }

  private def updateState(event: Any) = {
    event match {
      case event: PipelineDetails =>
        latestState = event.state
      case event: ClearLatestState =>
        latestState = None
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted => Nil
    case event: Any =>
      updateState(event)
  }

}

object PipelineActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[PipelineActor]),
      idExtractor = {
        case msg: GetPipeline =>
          (msg.id, msg)
        case msg: PipelineDetails =>
          (msg.id, msg)
      },
      shardResolver = {
        case msg: GetPipeline =>
          (msg.id.hashCode % 10).toString
        case msg: PipelineDetails =>
          (msg.id.hashCode % 10).toString
      })
  }
}
