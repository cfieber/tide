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
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.PersistentActor
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.model._
import AwsApi.{AwsReference, ServerGroupIdentity}
import scala.concurrent.duration.DurationInt

class ServerGroupActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  context.setReceiveTimeout(60 seconds)
  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)

  var awsReference: AwsReference[ServerGroupIdentity] = _
  var cloudDriver: Option[ActorRef] = None
  var latestState: Option[ServerGroupLatestState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {
    case wrapper: AwsResourceProtocol[_] =>
      val reference = wrapper.awsReference.asInstanceOf[AwsReference[ServerGroupIdentity]]
      val serverGroupEvent = wrapper.event.asInstanceOf[ServerGroupEvent]
      handleAwsResourceProtocol(reference, serverGroupEvent)

    case LatestStateTimeout =>
      latestState = None

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[ServerGroupIdentity],
                                        event: ServerGroupEvent) = event match {
    case event: GetServerGroup =>
      updateReferences(newAwsReference)
      sender() ! new ServerGroupDetails(newAwsReference, latestState)

    case event: ServerGroupLatestState =>
      updateReferences(newAwsReference)
      latestStateTimeout.cancel()
      latestStateTimeout = scheduler.scheduleOnce(60 seconds, self, LatestStateTimeout)
      if (latestState != Option(event)) {
        persist(event) { e =>
          updateState(event)
        }
      }
  }

  private def updateReferences(newAwsReference: AwsReference[ServerGroupIdentity]) = {
    awsReference = newAwsReference
  }

  private def updateState(event: ResourceEvent) = {
    event match {
      case event: ServerGroupLatestState =>
        latestState = Option(event)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case event: ServerGroupEvent =>
      updateState(event)
  }

}

object ServerGroupActor extends ClusteredActorObject {
  val props = Props[ServerGroupActor]
}


