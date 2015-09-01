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
import com.netflix.spinnaker.tide.actor.aws.PipelineActor
import com.netflix.spinnaker.tide.actor.aws.PipelineActor.PipelineDetails
import com.netflix.spinnaker.tide.actor.polling.PollingActor.Poll
import com.netflix.spinnaker.tide.actor.service.Front50ContractActorImpl
import com.netflix.spinnaker.tide.actor.service.Front50Actor.GetPipelines

class PipelinePollingActor() extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, PipelinePollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  val front50 = new Front50ContractActorImpl(clusterSharding)

  override def receive: Receive = {
    case msg: Poll =>
      pollScheduler.scheduleNextPoll(msg)
      handlePoll()
    case _ => Nil
  }

  def handlePoll(): Unit = {
    val foundPipelines = front50.ask(GetPipelines())
    foundPipelines.resources.foreach { pipeline =>
      clusterSharding.shardRegion(PipelineActor.typeName) ! PipelineDetails(pipeline.id, Option(pipeline.state))
    }
  }

}

object PipelinePollingActor extends PollingActorObject {
  val props = Props[PipelinePollingActor]

  case class PipelinePoll() extends Poll {
    val pollingIdentifier = "singleton"
  }
}
