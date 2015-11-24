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

package com.netflix.spinnaker.tide.actor.service

import java.lang.Boolean

import akka.actor.{ActorLogging, Props}
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, DescribeAutoScalingGroupsRequest}
import com.amazonaws.services.ec2.model.DescribeClassicLinkInstancesRequest
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.service.EddaActor.{RetrieveSecurityGroups, RetrieveLoadBalancers, RetrieveLaunchConfigurations, RetrieveAutoScalingGroups, RetrieveSubnets, RetrieveVpcs, RetrieveClassicLinkInstanceIds, FoundSecurityGroups, FoundLoadBalancers, FoundLaunchConfigurations, FoundAutoScalingGroups, FoundSubnets, FoundVpcs, FoundClassicLinkInstanceIds, EddaInit}
import com.netflix.spinnaker.tide.config.AwsConfig
import com.netflix.spinnaker.tide.model._
import AwsApi._
import scala.collection.JavaConversions._

class EddaActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var awsServiceProvider: Option[AwsServiceProvider] = None

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {
    case msg: EddaInit =>
      persist(msg) { it =>
        updateState(it)
        context become operational
      }
    case _ =>
  }

  def operational: Receive = {
    case msg: RetrieveSecurityGroups =>
      val amazonEc2 = awsServiceProvider.get.getAmazonEC2
      val securityGroups = amazonEc2.describeSecurityGroups.getSecurityGroups.map(AwsConversion.securityGroupFrom)
      sender ! FoundSecurityGroups(securityGroups)

    case msg: RetrieveLoadBalancers =>
      val loadBalancing = awsServiceProvider.get.getAmazonElasticLoadBalancing
      val loadBalancers = retrieveAll{ nextToken =>
        val result = loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest().withMarker(nextToken))
        (result.getLoadBalancerDescriptions.map(AwsConversion.loadBalancerFrom), Option(result.getNextMarker))
      }
      sender ! FoundLoadBalancers(loadBalancers)

    case msg: RetrieveLaunchConfigurations =>
      val autoScaling = awsServiceProvider.get.getAutoScaling
      val launchConfigurations = retrieveAll{ nextToken =>
        val result = autoScaling.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest().withNextToken(nextToken))
        (result.getLaunchConfigurations.map(AwsConversion.launchConfigurationFrom), Option(result.getNextToken))
      }
      sender ! FoundLaunchConfigurations(launchConfigurations)

    case msg: RetrieveAutoScalingGroups =>
      val autoScaling = awsServiceProvider.get.getAutoScaling
      val asgs = retrieveAll{ nextToken =>
        val result = autoScaling.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withNextToken(nextToken))
        (result.getAutoScalingGroups.map(AwsConversion.autoScalingGroupFrom), Option(result.getNextToken))
      }
      sender ! FoundAutoScalingGroups(asgs)

    case msg: RetrieveSubnets =>
      val amazonEc2 = awsServiceProvider.get.getAmazonEC2
      val subnets = amazonEc2.describeSubnets().getSubnets.map(AwsConversion.subnetFrom)
      sender ! FoundSubnets(subnets)

    case msg: RetrieveVpcs =>
      val amazonEc2 = awsServiceProvider.get.getAmazonEC2
      val vpcs = amazonEc2.describeVpcs().getVpcs
      val vpcClassicLinks = amazonEc2.describeVpcClassicLink().getVpcs
      val vpcClassicLinkLookup: Map[String, Boolean] = vpcClassicLinks.map { vpcClassicLink =>
        vpcClassicLink.getVpcId -> vpcClassicLink.isClassicLinkEnabled
      }.toMap
      val combinedVpcAttributes = vpcs.map { vpc =>
        val classicLinkEnabled: Boolean = vpcClassicLinkLookup.getOrElse(vpc.getVpcId, false)
        AwsConversion.vpcFrom(vpc, classicLinkEnabled)
      }
      sender ! FoundVpcs(combinedVpcAttributes)

    case msg: RetrieveClassicLinkInstanceIds =>
      val amazonEc2 = awsServiceProvider.get.getAmazonEC2
      val instanceIds = retrieveAll{ nextToken =>
        val result = amazonEc2.describeClassicLinkInstances(new DescribeClassicLinkInstancesRequest().withNextToken(nextToken))
        (result.getInstances.map(_.getInstanceId), Option(result.getNextToken))
      }
      sender ! FoundClassicLinkInstanceIds(instanceIds)
  }

  def retrieveAll[T](retrieve: String => (Seq[T], Option[String])): Seq[T] = {
    var currentToken: String = ""
    var instanceIds: Seq[T] = Nil
    do {
      val (resources: Seq[T], nextToken: Option[String]) = retrieve(currentToken)
      currentToken = nextToken.getOrElse("")
      instanceIds ++= resources
    } while (currentToken.nonEmpty)
    instanceIds
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      awsServiceProvider match {
        case Some(provider) =>
          context become operational
        case None =>
      }
    case msg => updateState(msg)
  }

  def updateState(event: Any) = {
    event match {
      case msg: EddaInit =>
        awsServiceProvider = AwsConfig.awsServiceProviderFactory.getAwsServiceProvider(msg.location)
      case _ =>
    }
  }
}

sealed trait EddaProtocol extends Serializable

sealed trait EddaProtocolInput extends EddaProtocol with AkkaClustered {
  val location: AwsLocation
  val resourceType: Class[_] = this.getClass
  override def akkaIdentifier = s"${location.akkaIdentifier}.${resourceType.getCanonicalName}"
}

object EddaActor extends ClusteredActorObject {
  val props = Props[EddaActor]

  case class EddaInit(location: AwsLocation, eddaUrlTemplate: String, override val resourceType: Class[_])
    extends EddaProtocolInput

  case class RetrieveSecurityGroups(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveLoadBalancers(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveLaunchConfigurations(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveAutoScalingGroups(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveSubnets(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveVpcs(location: AwsLocation) extends EddaProtocolInput
  case class RetrieveClassicLinkInstanceIds(location: AwsLocation) extends EddaProtocolInput

  case class FoundSecurityGroups(resources: Seq[SecurityGroup]) extends EddaProtocol
  case class FoundLoadBalancers(resources: Seq[LoadBalancer]) extends EddaProtocol
  case class FoundLaunchConfigurations(resources: Seq[LaunchConfiguration]) extends EddaProtocol
  case class FoundAutoScalingGroups(resources: Seq[AutoScalingGroup]) extends EddaProtocol
  case class FoundSubnets(resources: Seq[Subnet]) extends EddaProtocol
  case class FoundVpcs(resources: Seq[Vpc]) extends EddaProtocol
  case class FoundClassicLinkInstanceIds(resources: Seq[String]) extends EddaProtocol

}
