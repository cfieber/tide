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
import com.netflix.spinnaker.tide.actor.service.Front50Actor
import com.netflix.spinnaker.tide.actor.service.Front50Actor.{FoundPipelines, GetPipelines}

class PipelinePollingActor() extends PollingActor {

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  var currentIds: Seq[String] = Nil

  override def receive: Receive = {
    case msg: Poll =>
      clusterSharding.shardRegion(Front50Actor.typeName) ! GetPipelines()

    case msg: FoundPipelines =>
      val pipelines = msg.resources
      val oldIds = currentIds
      currentIds = pipelines.map(_.id)
      val removedIds = oldIds.toSet -- currentIds.toSet
      removedIds.foreach { identity =>
        clusterSharding.shardRegion(PipelineActor.typeName) ! PipelineDetails(identity, None)
      }
      pipelines.foreach { pipeline =>
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
