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

import akka.actor.ActorSystem
import com.netflix.akka.spring.{ActorFactory, AkkaConfiguration}
import com.netflix.spinnaker.tide.actor.aws.{PipelinePollingActor, Front50Actor, CloudDriverActor, ResourceEventRoutingActor}
import com.netflix.spinnaker.tide.api.{Front50Service, CloudDriverService}
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE
import org.springframework.context.annotation.{Scope => BeanScope, _}

@Configuration
@Import(Array(classOf[AkkaConfiguration]))
class ActorConfiguration {

  @Autowired var system: ActorSystem = _
  @Autowired var actorFactory: ActorFactory = _
  @Autowired var cloudDriverService: CloudDriverService = _
  @Autowired var front50Service: Front50Service = _

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def broadcastActor: BroadcastActor = new BroadcastActor()

  @Bean
  def broadcaster: BroadcastActor.Ref =
    actorFactory.create(system, classOf[BroadcastActor])

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def awsResourceActor: ResourceEventRoutingActor = {
    new ResourceEventRoutingActor(cloudDriver, front50)
  }

  @Bean
  def resourceEventRouter: ResourceEventRoutingActor.Ref =
    actorFactory.create(system, classOf[ResourceEventRoutingActor])

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def cloudDriverActor: CloudDriverActor = {
    new CloudDriverActor(cloudDriverService)
  }

  @Bean
  def cloudDriver: CloudDriverActor.Ref =
    actorFactory.create(system, classOf[CloudDriverActor])

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def front50Actor: Front50Actor = {
    new Front50Actor(front50Service)
  }

  @Bean
  def front50: Front50Actor.Ref =
    actorFactory.create(system, classOf[Front50Actor])

}
