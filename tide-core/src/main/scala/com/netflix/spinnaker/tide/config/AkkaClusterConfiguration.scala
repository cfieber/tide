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
import com.netflix.spinnaker.config.OkHttpClientConfiguration
import com.netflix.spinnaker.tide.actor.aws._
import com.netflix.spinnaker.tide.actor.classiclink.{AttachClassicLinkActor, ClassicLinkInstancesActor}
import com.netflix.spinnaker.tide.actor.comparison.AttributeDiffActor
import com.netflix.spinnaker.tide.actor.copy.{ServerGroupDeepCopyActor, PipelineDeepCopyActor, DependencyCopyActor}
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPoll
import com.netflix.spinnaker.tide.actor.polling.PipelinePollingActor.PipelinePoll
import com.netflix.spinnaker.tide.actor.polling._
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor.CloudDriverInit
import com.netflix.spinnaker.tide.actor.service.EddaActor._
import com.netflix.spinnaker.tide.actor.service.Front50Actor.Front50Init
import com.netflix.spinnaker.tide.actor.service.{Front50Actor, EddaActor, CloudDriverActor}
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskDirector}
import com.netflix.spinnaker.tide.actor.ClusterTestActor
import com.netflix.spinnaker.tide.model.AwsApi._
import org.springframework.beans.factory.annotation.{Value, Autowired}
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}
import scala.beans.BeanProperty
import scala.collection.JavaConversions._

@Configuration
class AkkaClusterConfiguration {

  @Autowired var system: ActorSystem = _

  @Autowired var okHttpClientConfiguration: OkHttpClientConfiguration = _

  @Value("${cloudDriver.baseUrl}") var cloudDriverApiUrl: String = _
  @Value("${front50.baseUrl}") var front50ApiUrl: String = _

  @PostConstruct
  def initialize() = {
    OkHttpClientConfigurationHolder.okHttpClientConfiguration = okHttpClientConfiguration
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
    ClassicLinkInstancesActor.startCluster(clusterSharding)
    PipelineActor.startCluster(clusterSharding)

    VpcPollingActor.startCluster(clusterSharding)
    SubnetPollingActor.startCluster(clusterSharding)
    SecurityGroupPollingActor.startCluster(clusterSharding)
    LoadBalancerPollingActor.startCluster(clusterSharding)
    ServerGroupPollingActor.startCluster(clusterSharding)
    LaunchConfigPollingActor.startCluster(clusterSharding)
    PipelinePollingActor.startCluster(clusterSharding)
    ClassicLinkInstanceIdPollingActor.startCluster(clusterSharding)

    ServerGroupDeepCopyActor.startCluster(clusterSharding)
    PipelineDeepCopyActor.startCluster(clusterSharding)
    DependencyCopyActor.startCluster(clusterSharding)
    AttachClassicLinkActor.startCluster(clusterSharding)

    TaskDirector.startCluster(clusterSharding)
    TaskActor.startCluster(clusterSharding)

    AttributeDiffActor.startCluster(clusterSharding)
  }


  def initActors() = {
    clusterSharding.shardRegion(CloudDriverActor.typeName) ! CloudDriverInit(cloudDriverApiUrl)
    clusterSharding.shardRegion(Front50Actor.typeName) ! Front50Init(front50ApiUrl)

    clusterSharding.shardRegion(PipelinePollingActor.typeName) ! PipelinePoll()

    val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, SubnetPollingActor, ClassicLinkInstanceIdPollingActor,
      SecurityGroupPollingActor, LoadBalancerPollingActor, LaunchConfigPollingActor, ServerGroupPollingActor)
    val resourceTypes: List[Class[_]] =List(classOf[RetrieveSecurityGroups], classOf[RetrieveLoadBalancers],
      classOf[RetrieveLaunchConfigurations], classOf[RetrieveAutoScalingGroups], classOf[RetrieveSubnets],
      classOf[RetrieveVpcs], classOf[RetrieveClassicLinkInstanceIds])
    val accounts = eddaSettings.getAccountToRegionsMapping.keySet()
    for (account <- accounts) {
      val regions: java.util.List[String] = eddaSettings.getAccountToRegionsMapping.get(account)
      for (region <- regions) {
        val location = AwsLocation(account, region)
        for (resourceType <- resourceTypes) {
          clusterSharding.shardRegion(EddaActor.typeName) ! EddaInit(location, eddaSettings.getUrlTemplate,
            resourceType)
        }
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

object OkHttpClientConfigurationHolder {
  var okHttpClientConfiguration: OkHttpClientConfiguration = _
}