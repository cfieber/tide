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

import akka.actor._
import akka.contrib.pattern.ShardRegion.Passivate
import akka.contrib.pattern.ClusterSharding
import akka.persistence.PersistentActor
import com.netflix.spinnaker.tide.actor.ClusterTestActor.{Messages, GetMessages, NewMessage, MessageContent}
import scala.concurrent.duration.DurationInt

class ClusterTestActor() extends PersistentActor with ActorLogging {
  log.info(s"*** Create ClusterTestActor: $persistenceId")

  var messages: List[String] = Nil

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the entity when no activity
  context.setReceiveTimeout(10 seconds)

  override def receiveCommand: Receive = {
    case event: NewMessage =>
      persist(event) { event =>
        messages = s"${event.messageContent.content}" :: messages
        log.info(s"*** NewMessage: $event")
      }
    case event: GetMessages =>
      log.info(s"*** GetMessages: $event")
      sender() ! Messages(event.name, messages)
    case ReceiveTimeout =>
      log.info(s"*** ReceiveTimeout")
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def receiveRecover: Receive = {
    case event: NewMessage =>
      log.info(s"*** receiveRecover: $event")
      messages = s"**recovered** ${event.messageContent.content}" :: messages
    case event =>
      log.info(s"*** receiveRecover: $event")
  }

}

sealed trait MessageProtocol

object ClusterTestActor {
  val typeName: String = this.getClass.getCanonicalName

  case class GetMessages(name: String) extends MessageProtocol
  case class NewMessage(name: String, messageContent: MessageContent, index3: Int = 0) extends MessageProtocol
  case class Messages(name: String, messages: List[String]) extends MessageProtocol
  case class MessageContent(content: String, index3: Int = 0)

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
    typeName = typeName,
    entryProps = Some(Props[ClusterTestActor]),
    idExtractor = {
      case msg @ GetMessages(name) =>
        (name, msg)
      case msg @ NewMessage(name, _, _) =>
        (name, msg)
    },
    shardResolver = {
      case GetMessages(name) =>
        name.head.toString
      case NewMessage(name, _, _) =>
        name.head.toString
    })
  }
}
