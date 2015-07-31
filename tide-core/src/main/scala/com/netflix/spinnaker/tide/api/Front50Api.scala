package com.netflix.spinnaker.tide.api

import com.fasterxml.jackson.annotation.{JsonUnwrapped, JsonProperty}
import com.netflix.spinnaker.tide.actor.aws.AwsApi
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{LoadBalancerIdentity, AwsLocation}

import scala.collection.immutable.HashMap
import scala.collection.mutable

sealed trait Front50Api

case class Pipeline(id: String, @JsonUnwrapped @JsonProperty("state") state: PipelineState)

case class PipelineState(name: String, application: String, triggers: List[Map[String, Any]],
                         stages: List[Map[String, Any]]) extends Front50Api {

  def disableTriggers(): PipelineState = {
    val newTriggers: List[Map[String, Any]] = triggers.map { trigger =>
      trigger + ("enabled" -> false)
    }
    this.copy(triggers = newTriggers)
  }

  def applyVisitor(clusterVisitor: ClusterVisitor): PipelineState = {
    val newStages: List[Map[String, Any]] = stages.map { stage =>
      stage("type") match {
        case "deploy" =>
          val clusters = stage("clusters").asInstanceOf[List[Map[String, Any]]]
          val newClusters = clusters.map { cluster =>
            clusterVisitor.visit(Cluster(cluster)).attributes
          }
          stage + ("clusters" -> newClusters)
        case "canary" =>
          val clusterPairs = stage("clusterPairs").asInstanceOf[List[Map[String, Map[String, Any]]]]
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

trait ClusterVisitor {
  def visit(cluster: Cluster): Cluster
}

case class ClusterVpcMigrator(sourceVpcName: Option[String], targetVpcName: String,
                              securityGroupIdMappingByLocation: Map[AwsLocation, Map[String, String]]) extends ClusterVisitor {
  def visit(cluster: Cluster): Cluster = {
    val location = AwsLocation(cluster.getAccount, cluster.getRegion)
    val subnetType = cluster.getSubnetType
    val vpcName = AwsApi.getVpcNameFromSubnetType(subnetType)
    if (vpcName == sourceVpcName) {
      val newSubnetType = AwsApi.constructTargetSubnetType(subnetType, Option(targetVpcName))
      val newLoadBalancers = cluster.getLoadBalancersNames.map(LoadBalancerIdentity(_)
        .forVpc(Option(targetVpcName)).loadBalancerName)
      val newSecurityGroups = securityGroupIdMappingByLocation.get(location) match {
        case Some(securityGroupIdsSourceToTarget) =>
          cluster.getSecurityGroupIds.map(securityGroupIdsSourceToTarget.getOrElse(_, "nonexistant"))
        case None =>
          cluster.getSecurityGroupIds
      }
      cluster.setSubnetType (newSubnetType).setLoadBalancersNames(newLoadBalancers)
        .setSecurityGroupIds(newSecurityGroups)
    } else {
      cluster
    }
  }
}

case class ClusterDependencyCollector() extends ClusterVisitor {
  var dependencies: List[ClusterDependencies] = Nil

  def visit(cluster: Cluster): Cluster = {
    val clusterDependencies = ClusterDependencies(cluster.getAccount, cluster.getRegion, cluster.getSubnetType,
      cluster.getLoadBalancersNames, cluster.getSecurityGroupIds)
    dependencies ::= clusterDependencies
    cluster
  }
}

case class ClusterDependencies(account: String, region: String, subnetType: Option[String],
                        loadBalancersNames: Set[String], securityGroupIds: Set[String])

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

  def getLoadBalancersNames = {
    attributes("loadBalancers").asInstanceOf[List[String]].toSet
  }
  def setLoadBalancersNames(newLoadBalancers: Set[String]): Cluster = {
    Cluster(attributes + ("loadBalancers" -> newLoadBalancers))
  }

  def getSecurityGroupIds = {
    attributes("securityGroups").asInstanceOf[List[String]].toSet
  }
  def setSecurityGroupIds(newSecurityGroupIds: Set[String]): Cluster = {
    Cluster(attributes + ("securityGroups" -> newSecurityGroupIds))
  }
}

//case class Stage(@JsonProperty("type") stageType: String,
//                 @JsonProperty("name") name: String,
//                 @JsonProperty("deploy") @JsonUnwrapped deploy: Deploy,
//                 @JsonProperty("attributes") @JsonUnwrapped attributes: Map[String, Any])
//
//case class Trigger(enabled: Boolean, @JsonProperty("type") triggerType: String, master: String, job: String)
//
//case class Deploy(clusters: List[Cluster])
//
//case class Cluster(account: String, application: String, stack: String, freeFormDetails: String,
//                   loadBalancers: List[String], subnetType: String, securityGroups: List[String],
//                   @JsonProperty("attributes") @JsonUnwrapped attributes: Map[String, Any])
