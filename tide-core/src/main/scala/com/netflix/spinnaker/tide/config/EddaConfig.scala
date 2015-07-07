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

import akka.actor.ActorRef
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.aws.AwsApi.AwsLocation
import com.netflix.spinnaker.tide.actor.aws.PollingActor.Start
import com.netflix.spinnaker.tide.actor.aws._
import com.netflix.spinnaker.tide.api.EddaService
import org.springframework.beans.factory.annotation.{Value, Autowired}
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}
import retrofit.RestAdapter
import retrofit.client.Client

import scala.beans.BeanProperty
import scala.collection.JavaConversions._

@Configuration
class EddaConfig {

  @Autowired var retrofitClient: Client = _
  @Autowired var clusterSharding: ClusterSharding = _
  @Autowired var cloudDriver: CloudDriverActor.Ref = _

  @PostConstruct
  def startPollingEdda(): Unit = {
    val securityGroupPollingCluster: ActorRef = clusterSharding.shardRegion(SecurityGroupPollingActor.typeName)
    val loadBalancerPollingCluster: ActorRef = clusterSharding.shardRegion(LoadBalancerPollingActor.typeName)
    val serverGroupPollingCluster: ActorRef = clusterSharding.shardRegion(ServerGroupPollingActor.typeName)
    val vpcPollingCluster: ActorRef = clusterSharding.shardRegion(VpcPollingActor.typeName)
    val accounts = eddaSettings.getAccountToRegionsMapping.keySet()
    for (account <- accounts) {
      val regions: java.util.List[String] = eddaSettings.getAccountToRegionsMapping.get(account)
      for (region <- regions) {
        securityGroupPollingCluster ! Start(account, region, eddaSettings.urlTemplate, cloudDriver)
        loadBalancerPollingCluster ! Start(account, region, eddaSettings.urlTemplate, cloudDriver)
        serverGroupPollingCluster ! Start(account, region, eddaSettings.urlTemplate, cloudDriver)
        vpcPollingCluster ! Start(account, region, eddaSettings.urlTemplate, cloudDriver)
      }
    }
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

case class EddaServices(locationToService: Map[AwsLocation, EddaService] = Map.empty) {

  def getLocations: Set[AwsLocation] = {
    locationToService.keySet
  }

}

