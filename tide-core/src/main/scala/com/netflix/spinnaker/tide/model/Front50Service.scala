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

package com.netflix.spinnaker.tide.model

import com.fasterxml.jackson.annotation.{JsonProperty, JsonUnwrapped}
import com.netflix.spinnaker.tide.model.Front50Service.{PipelineState, Pipeline}
import retrofit.http.{Body, POST, GET, Headers}

trait Front50Service {
  @Headers(Array("Accept: application/json"))
  @GET("/pipelines")
  def getAllPipelines: Seq[Pipeline]

  @Headers (Array ("Accept: application/json") )
  @POST ("/pipelines") def savePipeline(@Body pipeline: PipelineState): String
}

object Front50Service {

  case class Pipeline(id: String, @JsonUnwrapped @JsonProperty("state") state: PipelineState)

  case class PipelineState(name: String, application: String, parallel: Boolean, triggers: Seq[Map[String, Any]],
                           stages: Seq[Map[String, Any]]) {

    def disableTriggers(): PipelineState = {
      val newTriggers: Seq[Map[String, Any]] = triggers.map { trigger =>
        trigger + ("enabled" -> false)
      }
      this.copy(triggers = newTriggers)
    }

    def applyVisitor(clusterVisitor: ClusterVisitor): PipelineState = {
      val newStages: Seq[Map[String, Any]] = stages.map { stage =>
        stage("type") match {
          case "deploy" =>
            val clusters = stage("clusters").asInstanceOf[Seq[Map[String, Any]]]
            val newClusters = clusters.map { cluster =>
              clusterVisitor.visit(Cluster(cluster)).attributes
            }
            stage + ("clusters" -> newClusters)
          case "canary" =>
            val clusterPairs = stage("clusterPairs").asInstanceOf[Seq[Map[String, Map[String, Any]]]]
            val newClusterPairs = clusterPairs.map { clusterPair =>
              var newClusterPair = clusterPair
              val newBaseline = clusterVisitor.visit(Cluster(clusterPair("baseline"))).attributes
              newClusterPair += ("baseline" -> newBaseline)
              val newCanary = clusterVisitor.visit(Cluster(clusterPair("canary"))).attributes
              newClusterPair += ("canary" -> newCanary)
              newClusterPair
            }
            stage + ("clusterPairs" -> newClusterPairs)
          case _ => stage
        }
      }
      this.copy(stages = newStages)
    }
  }

  case class Cluster(attributes: Map[String, Any]) {
    def getAccount = {
      attributes("account").asInstanceOf[String]
    }
    def getRegion = {
      val availabilityZones = attributes("availabilityZones").asInstanceOf[Map[String, String]]
      availabilityZones.keySet.head
    }

    def getSubnetType = {
      attributes.get("subnetType").asInstanceOf[Option[String]]
    }
    def setSubnetType(subnetTypeOption: Option[String]): Cluster = {
      subnetTypeOption match {
        case None => Cluster(attributes)
        case Some(subnetType) => Cluster(attributes + ("subnetType" -> subnetType))
      }
    }

    def getApplication: String = {
      attributes("application").asInstanceOf[String]
    }
    def setAppName(application: String): Cluster = {
      Cluster(attributes + ("application" -> application))
    }

    def getLoadBalancersNames = {
      attributes.getOrElse("loadBalancers", Nil).asInstanceOf[Seq[String]].toSet
    }
    def setLoadBalancersNames(newLoadBalancers: Set[String]): Cluster = {
      Cluster(attributes + ("loadBalancers" -> newLoadBalancers))
    }

    def getSecurityGroupIds = {
      attributes.getOrElse("securityGroups", Nil).asInstanceOf[Seq[String]].toSet
    }
    def setSecurityGroupIds(newSecurityGroupIds: Set[String]): Cluster = {
      Cluster(attributes + ("securityGroups" -> newSecurityGroupIds))
    }
  }

  trait ClusterVisitor {
    def visit(cluster: Cluster): Cluster
  }

}

