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
import com.netflix.spinnaker.tide.actor.aws._
import com.netflix.spinnaker.tide.actor.copy.{ServerGroupDeepCopyActor, PipelineDeepCopyActor, DependencyCopyActor}
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPoll
import com.netflix.spinnaker.tide.actor.polling.PipelinePollingActor.PipelinePoll
import com.netflix.spinnaker.tide.actor.polling._
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor.CloudDriverInit
import com.netflix.spinnaker.tide.actor.service.EddaActor.EddaInit
import com.netflix.spinnaker.tide.actor.service.Front50Actor.Front50Init
import com.netflix.spinnaker.tide.actor.service.{Front50Actor, EddaActor, CloudDriverActor}
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskDirector}
import com.netflix.spinnaker.tide.actor.ClusterTestActor
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation
import org.springframework.beans.factory.annotation.{Value, Autowired}
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}
import scala.beans.BeanProperty
import scala.collection.JavaConversions._

@Configuration
class AkkaClusterConfiguration {

  @Autowired var system: ActorSystem = _

  @Value("${cloudDriver.baseUrl}") var cloudDriverApiUrl: String = _
  @Value("${front50.baseUrl}") var front50ApiUrl: String = _

  @PostConstruct
  def initialize() = {
    startClusters()
    initActors()
  }

  def startClusters() = {
    ClusterTestActor.startCluster(clusterSharding)

    EddaActor.startCluster(clusterSharding)
    CloudDriverActor.startCluster(clusterSharding)
    Front50Actor.startCluster(clusterSharding)

    SecurityGroupActor.startCluster(clusterSharding)
    LoadBalancerActor.startCluster(clusterSharding)
    ServerGroupActor.startCluster(clusterSharding)
    PipelineActor.startCluster(clusterSharding)

    VpcPollingActor.startCluster(clusterSharding)
    SubnetPollingActor.startCluster(clusterSharding)
    SecurityGroupPollingActor.startCluster(clusterSharding)
    LoadBalancerPollingActor.startCluster(clusterSharding)
    ServerGroupPollingActor.startCluster(clusterSharding)
    PipelinePollingActor.startCluster(clusterSharding)

    ServerGroupDeepCopyActor.startCluster(clusterSharding)
    PipelineDeepCopyActor.startCluster(clusterSharding)
    DependencyCopyActor.startCluster(clusterSharding)

    TaskDirector.startCluster(clusterSharding)
    TaskActor.startCluster(clusterSharding)
  }


  def initActors() = {
    clusterSharding.shardRegion(CloudDriverActor.typeName) ! CloudDriverInit(cloudDriverApiUrl)
    clusterSharding.shardRegion(Front50Actor.typeName) ! Front50Init(front50ApiUrl)

    clusterSharding.shardRegion(PipelinePollingActor.typeName) ! PipelinePoll()

    val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, SubnetPollingActor,
      SecurityGroupPollingActor, LoadBalancerPollingActor, ServerGroupPollingActor)
    val accounts = eddaSettings.getAccountToRegionsMapping.keySet()
    for (account <- accounts) {
      val regions: java.util.List[String] = eddaSettings.getAccountToRegionsMapping.get(account)
      for (region <- regions) {
        val location = AwsLocation(account, region)
        clusterSharding.shardRegion(EddaActor.typeName) ! EddaInit(location, eddaSettings.getUrlTemplate)
        for (poller <- pollers) {
          clusterSharding.shardRegion(poller.typeName) ! EddaPoll(location)
        }
      }
    }
  }


  @Bean
  def clusterSharding: ClusterSharding = {
    ClusterSharding(system)
  }

  @Bean
  @ConfigurationProperties("edda")
  def eddaSettings: EddaSettings = {
    new EddaSettings()
  }

}

class EddaSettings {
  @BeanProperty var urlTemplate: String = _
  @BeanProperty var accountToRegionsMapping: java.util.HashMap[String, java.util.List[String]] = _
}
