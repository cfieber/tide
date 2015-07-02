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
import com.netflix.spinnaker.tide.actor.sync.{AwsResourceActor, CloudDriverActor}
import com.netflix.spinnaker.tide.api.CloudDriverService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE
import org.springframework.context.annotation.{Scope => BeanScope, _}

@Configuration
@Import(Array(classOf[AkkaConfiguration]))
class ActorConfiguration {

  @Autowired var system: ActorSystem = _
  @Autowired var actorFactory: ActorFactory = _
  @Autowired var cloudDriverService: CloudDriverService = _

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def broadcastActor: BroadcastActor = new BroadcastActor()

  @Bean
  def broadcaster: BroadcastActor.Ref =
    actorFactory.create(system, classOf[BroadcastActor])

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def awsResourceActor: AwsResourceActor = {
    new AwsResourceActor(cloudDriver)
  }

  @Bean
  def awsResource: AwsResourceActor.Ref =
    actorFactory.create(system, classOf[AwsResourceActor])

  @Bean
  @BeanScope(SCOPE_PROTOTYPE)
  def cloudDriverActor: CloudDriverActor = {
    new CloudDriverActor(cloudDriverService)
  }

  @Bean
  def cloudDriver: CloudDriverActor.Ref =
    actorFactory.create(system, classOf[CloudDriverActor])

}
