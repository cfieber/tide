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

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.PersistentActor
import com.netflix.spinnaker.tide.actor.aws.PollingActor.{PollingClustered, Poll, Start}
import scala.concurrent.duration.DurationInt

trait PollingActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledPolling = scheduler.schedule(0 seconds, 15 seconds, self, Poll())
  override def postStop(): Unit = scheduledPolling.cancel()

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def receiveCommand: Receive = {
    case event: Start =>
      persist(event) { it =>
        updateState(it)
        start()
      }

    case event: Poll =>
      poll()
  }

  def start()

  def poll()

  def updateState(event: Start)

}

trait PollingProtocol extends Serializable

object PollingActor {
  trait Start extends PollingProtocol
  case class Poll() extends PollingProtocol

  trait PollingClustered extends PollingProtocol {
    def pollingIdentifier: String
  }
}

trait PollingActorObject {
  val typeName: String = this.getClass.getCanonicalName
  def props: Props

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(props),
      idExtractor = {
        case msg: PollingClustered =>
          (msg.pollingIdentifier, msg)
      },
      shardResolver = {
        case msg: PollingClustered =>
          (msg.pollingIdentifier.hashCode % 10).toString
      })
  }

}


