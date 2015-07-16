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

import javax.annotation.PostConstruct

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.aws._
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.{Bean, Configuration}


@Configuration
class AkkaClusterConfiguration {

  @Autowired var system: ActorSystem = _

  @PostConstruct
  def initialize() = {
    ClusterTestActor.startCluster(clusterSharding)

    SecurityGroupActor.startCluster(clusterSharding)
    LoadBalancerActor.startCluster(clusterSharding)
    ServerGroupActor.startCluster(clusterSharding)

    VpcPollingActor.startCluster(clusterSharding)
    SubnetPollingActor.startCluster(clusterSharding)
    SecurityGroupPollingActor.startCluster(clusterSharding)
    LoadBalancerPollingActor.startCluster(clusterSharding)
    ServerGroupPollingActor.startCluster(clusterSharding)

    ServerGroupCloneActor.startCluster(clusterSharding)
    TaskDirector.startCluster(clusterSharding)
    TaskActor.startCluster(clusterSharding)
  }

  @Bean
  def clusterSharding: ClusterSharding = {
    ClusterSharding(system)
  }

}
