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

package com.netflix.spinnaker.tide.actor.service

import akka.actor.Props
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.service.Front50Actor.{FoundPipelines, GetPipelines, AddPipelines}
import com.netflix.spinnaker.tide.model.Front50Service
import com.netflix.spinnaker.tide.model.Front50Service.{Pipeline, PipelineState}

class Front50Actor extends RetrofitServiceActor[Front50Service] {

  override def operational: Receive = {
    case msg: AddPipelines =>
      service.addPipelines(msg.pipelines)
    case msg: GetPipelines =>
      sender ! FoundPipelines(service.getAllPipelines)
  }

}

sealed trait Front50Protocol extends Serializable

object Front50Actor extends SingletonActorObject {
  val props = Props[Front50Actor]

  case class Front50Init(url: String) extends Front50Protocol
    with RetrofitServiceInit[Front50Service] {
    override val serviceType: Class[Front50Service] = classOf[Front50Service]
  }

  case class GetPipelines() extends Front50Protocol
  case class FoundPipelines(resources: Seq[Pipeline]) extends Front50Protocol
  case class AddPipelines(pipelines: Seq[PipelineState]) extends Front50Protocol

}
