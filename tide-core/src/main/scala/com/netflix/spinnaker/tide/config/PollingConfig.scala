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

package com.netflix.spinnaker.tide.config

import javax.annotation.PostConstruct

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import com.netflix.akka.spring.ActorFactory
import com.netflix.spinnaker.tide.actor.aws.EddaPollingActor.EddaStart
import com.netflix.spinnaker.tide.actor.aws._
import com.netflix.spinnaker.tide.actor.aws.PipelinePollingActor.PipelineStart
import com.netflix.spinnaker.tide.api.Front50Service
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory._
import org.springframework.context.annotation.{Scope, Bean, Configuration}
import scala.collection.JavaConversions._

@Configuration
class PollingConfig {

  @Autowired var system: ActorSystem = _
  @Autowired var actorFactory: ActorFactory = _
  @Autowired var front50Service: Front50Service = _
  @Autowired var eddaSettings: EddaSettings = _
  @Autowired var cloudDriver: CloudDriverActor.Ref = _
  @Autowired var clusterSharding: ClusterSharding = _

  @PostConstruct
  def startPolling() = {
    pipelinePoller ! PipelineStart
    val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, SubnetPollingActor,
      SecurityGroupPollingActor, LoadBalancerPollingActor, ServerGroupPollingActor)
    val accounts = eddaSettings.getAccountToRegionsMapping.keySet()
    for (account <- accounts) {
      val regions: java.util.List[String] = eddaSettings.getAccountToRegionsMapping.get(account)
      for (region <- regions) {
        val start = EddaStart(account, region, eddaSettings.urlTemplate, cloudDriver)
        for (poller <- pollers) {
          clusterSharding.shardRegion(poller.typeName) ! start
        }
      }
    }
  }

  @Bean
  @Scope(SCOPE_PROTOTYPE)
  def pipelinePollingActor: PipelinePollingActor = new PipelinePollingActor(front50Service)

  @Bean
  def pipelinePoller: PipelinePollingActor.Ref =
    actorFactory.create(system, classOf[PipelinePollingActor])
}
