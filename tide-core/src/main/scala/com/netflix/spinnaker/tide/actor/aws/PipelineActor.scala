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

import akka.actor.{ActorLogging, PoisonPill, Props}
import akka.contrib.pattern.ShardRegion._
import akka.persistence.{PersistentActor, RecoveryCompleted}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.PipelineActor.{PipelineDetails, GetPipeline}
import com.netflix.spinnaker.tide.model.Front50Service.PipelineState
import com.netflix.spinnaker.tide.model.{ClearLatestState, LatestStateTimeout}

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

sealed trait PipelineProtocol extends Serializable {
  def id: String
}

object PipelineActor extends ClusteredActorObject {
  val props = Props[PipelineActor]

  override def idExtractor: IdExtractor = {
    case msg: PipelineProtocol =>
      (msg.id, msg)
  }
  override def shardResolver: ShardResolver = {
    case msg: PipelineProtocol =>
      (msg.id.hashCode % 10).toString
  }

  case class GetPipeline(id: String) extends PipelineProtocol
  case class PipelineDetails(id: String, state: Option[PipelineState]) extends PipelineProtocol

}

