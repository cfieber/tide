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

import akka.actor.{Props, ActorRef}
import akka.persistence.RecoveryCompleted
import com.netflix.spinnaker.tide.actor.PipelineActor
import com.netflix.spinnaker.tide.actor.aws.ResourceEventRoutingActor.PipelineDetails
import com.netflix.spinnaker.tide.actor.aws.PipelinePollingActor.PipelineStart
import com.netflix.spinnaker.tide.actor.aws.PollingActor.{Poll, Start}
import com.netflix.spinnaker.tide.api.Front50Service

class PipelinePollingActor(private val front50Service: Front50Service) extends PollingActor {

  override def start(): Unit = {
  }

  def poll(): Unit = {
    val pipelines = front50Service.getAllPipelines
    pipelines.foreach { pipeline =>
      getShardCluster(PipelineActor.typeName) ! PipelineDetails(pipeline.id, Option(pipeline.state))
    }
  }

  def updateState(event: Start) = {
    event match {
      case event: PipelineStart =>
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event: Start =>
      updateState(event)
  }

}

object PipelinePollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[SecurityGroupPollingActor]

  case class PipelineStart() extends Start
}
