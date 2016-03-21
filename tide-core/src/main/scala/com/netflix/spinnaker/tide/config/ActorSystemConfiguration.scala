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

import akka.actor.ActorSystem
import com.netflix.spinnaker.config.OkHttpClientConfiguration
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor.CloudDriverInit
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.{Qualifier, Autowired, Value}
import org.springframework.context.annotation.{Bean, Configuration, Primary}
import retrofit.RetrofitError
import retrofit.client.OkClient

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

@Configuration
class ActorSystemConfiguration {

  @Autowired var okHttpClientConfiguration: OkHttpClientConfiguration = _

  @Value("${akka.cluster.port:2551}") var clusterPort: String = _
  @Value("${akka.actor.system.name:default}") var actorSystemName: String = _
  @Value("${cloudDriver.baseUrl}") var cloudDriverApiUrl: String = _
  @Value("${redis.host}") var redisHost: String = _
  @Value("${redis.port}") var redisPort: Int = _

  @Bean
  def actorSystem(@Value("${akka.actor.system.name:default}") name: String,
                  @Qualifier("akkaConfig") akkaConfig: Config): ActorSystem = {
    val system = ActorSystem.create(name, akkaConfig.withFallback(ConfigFactory.load()))
    system
  }

  @Bean
  @Primary
  def akkaConfig(): Config = {
    var currentIp = "127.0.0.1"
    var seeds: Seq[String] = List(s"akka.tcp://$actorSystemName@$currentIp:$clusterPort")
    val netflixCluster: Option[String] = sys.env.get("NETFLIX_CLUSTER")
    netflixCluster.foreach { currentCluster =>
        currentIp = sys.env("EC2_LOCAL_IPV4")
        val currentApp = sys.env("NETFLIX_APP")
        val currentAccount = sys.env("NETFLIX_ACCOUNT")
        val okClient = new OkClient(okHttpClientConfiguration.create())
        val cloudDriverService = CloudDriverInit(cloudDriverApiUrl).constructService(okClient)
        try {
          val clusterDetail = cloudDriverService.getClusterDetail(currentApp, currentAccount, currentCluster).get(0)
          if (clusterDetail.serverGroups.nonEmpty) {
            val allInstancesInCluster = clusterDetail.serverGroups.flatMap(_.instances)
            seeds = allInstancesInCluster map(instance => s"akka.tcp://$actorSystemName@${instance.privateIpAddress}:$clusterPort")
          }
        } catch {
          case e: RetrofitError =>
            log.info(e.getMessage)
        }
    }
    var config: Config = ConfigFactory.empty()
      .withValue("redis.host", ConfigValueFactory.fromAnyRef(redisHost))
      .withValue("redis.port", ConfigValueFactory.fromAnyRef(redisPort))
      .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(clusterPort))
      .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(currentIp))
      .withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seeds.asJava))
    config = config withFallback ConfigFactory.load()
    log.info(s"Akka config: $config")
    config
  }

  private val log = LoggerFactory.getLogger(classOf[ActorSystemConfiguration])
}
