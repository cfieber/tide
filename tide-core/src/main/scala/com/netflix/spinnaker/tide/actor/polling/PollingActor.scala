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

import akka.actor.{Actor, ActorLogging}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject

trait PollingActor extends Actor with ActorLogging {

  def pollScheduler: PollScheduler

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

}

trait PollingProtocol extends Serializable {
  def pollingIdentifier: String
}

object PollingActor {
  trait Poll extends PollingProtocol
}

trait PollingActorObject extends ClusteredActorObject {
  override def idExtractor = {
    case msg: PollingProtocol =>
      (s"$typeName.${msg.pollingIdentifier}", msg)
  }
  override def shardResolver = {
    case msg: PollingProtocol =>
      (s"$typeName.${msg.pollingIdentifier}".hashCode % 10).toString
  }
}


