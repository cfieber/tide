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

package com.netflix.spinnaker.tide.actor.aws

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonUnwrapped}
import com.fasterxml.jackson.databind.ObjectMapper

import scala.beans.BeanProperty

object AwsApi {

  case class AwsLocation(account: String, region: String) extends AkkaClustered {
    @JsonIgnore val akkaIdentifier = s"$account.$region"
  }

  case class VpcLocation(@JsonUnwrapped location: AwsLocation, vpcName: Option[String]) extends AkkaClustered {
    @JsonIgnore val akkaIdentifier = s"${location.akkaIdentifier}.${vpcName.getOrElse("")}"
  }

  trait AwsIdentity extends AkkaClustered

  case class AwsReference[T <: AwsIdentity](location: AwsLocation, identity: T) extends AkkaClustered {
    @JsonIgnore val akkaIdentifier = s"${location.akkaIdentifier}.${identity.akkaIdentifier}"
  }

  case class Tag(key: String, value: String)

  case class SecurityGroup(groupId: String,
                           @JsonUnwrapped @JsonProperty("name") identity: SecurityGroupIdentity,
                           @JsonUnwrapped @JsonProperty("state") state: SecurityGroupState)

  case class SecurityGroupIdentity(groupName: String, vpcId: Option[String] = None) extends AwsIdentity {
    @JsonIgnore val akkaIdentifier: String = s"SecurityGroup.$groupName.${vpcId.getOrElse("")}"
  }

  case class SecurityGroupState(description: String, ipPermissions: Set[IpPermission]) {
    def ensureSecurityGroupNameOnIngressRules(securityGroupIdToName: Map[String, SecurityGroupIdentity]): SecurityGroupState = {
      val newIpPermissions = ipPermissions.map { ipPermission =>
        val newUserIdGroupPairs = ipPermission.userIdGroupPairs.map {
          case pair@UserIdGroupPairs(_, Some(groupName)) => pair
          case pair@UserIdGroupPairs(Some(groupId), None) =>
            UserIdGroupPairs(Option(groupId), securityGroupIdToName.get(groupId).map(_.groupName))
        }
        ipPermission.copy(userIdGroupPairs = newUserIdGroupPairs)
      }
      copy(ipPermissions = newIpPermissions)
    }
  }

  case class IpPermission(fromPort: Int,
                          toPort: Int,
                          ipProtocol: String,
                          ipRanges: Set[String],
                          userIdGroupPairs: Set[UserIdGroupPairs])

  case class UserIdGroupPairs(groupId: Option[String], groupName: Option[String])


  case class LoadBalancer(@JsonUnwrapped @JsonProperty("identity") identity: LoadBalancerIdentity,
                          @JsonUnwrapped @JsonProperty("state") state: LoadBalancerState)

  case class LoadBalancerIdentity(loadBalancerName: String) extends AwsIdentity {
    @JsonIgnore val akkaIdentifier: String = s"LoadBalancer.$loadBalancerName"

    @JsonIgnore def forVpc(vpcNameOption: Option[String]): LoadBalancerIdentity = {
      vpcNameOption match {
        case None => this
        case Some(vpcName) =>
          val targetName = loadBalancerName match {
            case s if s.endsWith("-frontend") =>
              s"${s.dropRight("-frontend".length)}-$vpcName"
            case s if s.endsWith("-vpc") =>
              s"${loadBalancerName.dropRight("-vpc".length)}-$vpcName"
            case s if s.endsWith(s"-$vpcName") =>
              loadBalancerName
            case s =>
              s"$loadBalancerName-$vpcName"
          }
          LoadBalancerIdentity(targetName)
      }
    }

    @JsonIgnore def isConsistentWithVpc(vpcNameOption: Option[String]): Boolean = {
      vpcNameOption match {
        case None => loadBalancerName.endsWith("-frontend")
        case Some(vpcName) => loadBalancerName.endsWith(s"-$vpcName")
      }
    }
  }

  def constructTargetSubnetType(sourceSubnetType: Option[String], targetVpcName: Option[String]): Option[String] = {
    sourceSubnetType.map{ subnetType =>
      val cleanSubnetType = subnetType.replaceAll("DEPRECATED_", "").replaceAll("-elb", "").replaceAll("-ec2", "")
      targetVpcName match {
        case Some(vpcName) => s"${cleanSubnetType.split(" ").head} ($vpcName)"
        case None => s"${cleanSubnetType.split(" ").head}"
      }
    }
  }

  def normalizeSecurityGroupNames(securityGroups: Set[String], securityGroupIdToName: Map[String, SecurityGroupIdentity]): Set[String] = {
    securityGroups.map { securityGroupName =>
      if (securityGroupName.startsWith("sg-")) {
        securityGroupIdToName.get(securityGroupName) match {
          case None => securityGroupName
          case Some(identity) => identity.groupName
        }
      } else {
        securityGroupName
      }
    }
  }

  case class LoadBalancerState(createdTime: Long, @JsonProperty("VPCId") vpcId: Option[String],
                               availabilityZones: Set[String],
                               healthCheck: HealthCheck, listenerDescriptions: Set[ListenerDescription],
                               scheme: String, securityGroups: Set[String],
                               sourceSecurityGroup: ElbSourceSecurityGroup, subnets: Set[String],
                               subnetType: Option[String]) {

    def forVpc(vpcName: Option[String], vpcId: Option[String]): LoadBalancerState = {
      this.copy(vpcId = vpcId, subnetType = constructTargetSubnetType(subnetType, vpcName))
    }

    def convertToSecurityGroupNames(securityGroupIdToName: Map[String, SecurityGroupIdentity]): LoadBalancerState = {
      copy(securityGroups = normalizeSecurityGroupNames(securityGroups, securityGroupIdToName))
    }

    def populateVpcAttributes(vpcs: List[Vpc], subnetDetails: List[Subnet]): LoadBalancerState = {
      val loadBalancerState: Option[LoadBalancerState] = subnets.headOption.flatMap { subnetId =>
        val subnetOption = subnetDetails.find(_.subnetId == subnetId)
        subnetOption.map { subnet =>
          copy(subnetType = Option(subnet.subnetType), vpcId = Option(subnet.vpcId))
        }
      }
      loadBalancerState match {
        case Some(state) => state
        case None => this
      }
    }
  }

  case class HealthCheck(healthyThreshold: Int, interval: Int, target: String, timeout: Int, unhealthyThreshold: Int)

  case class ListenerDescription(listener: Listener, policyNames: Set[String])

  case class Listener(SSLCertificateId: Option[String], instancePort: Int, instanceProtocol: String, loadBalancerPort: Int,
                      protocol: String)

  case class ElbSourceSecurityGroup(groupName: String, ownerAlias: String)

  case class LaunchConfiguration(@JsonUnwrapped @JsonProperty("identity") identity: LaunchConfigurationIdentity,
                                 @JsonUnwrapped @JsonProperty("state") state: LaunchConfigurationState)

  case class LaunchConfigurationIdentity(launchConfigurationName: String) extends AwsIdentity {
    @JsonIgnore val akkaIdentifier: String = s"LaunchConfiguration.$launchConfigurationName"
  }

  case class LaunchConfigurationState(createdTime: Long,
                                      associatePublicIpAddress: Option[Boolean],
                                      ebsOptimized: Boolean, iamInstanceProfile: String, imageId: String,
                                      instanceMonitoring: InstanceMonitoring, instanceType: String, kernelId: String,
                                      keyName: String, ramdiskId: String,
                                      securityGroups: Set[String], spotPrice: Option[String]) {
    def convertToSecurityGroupNames(securityGroupIdToName: Map[String, SecurityGroupIdentity]): LaunchConfigurationState = {
      copy(securityGroups = normalizeSecurityGroupNames(securityGroups, securityGroupIdToName))
    }
  }

  case class InstanceMonitoring(enabled: Boolean)

  case class AutoScalingGroup(@JsonUnwrapped @JsonProperty("identity") identity: AutoScalingGroupIdentity,
                              @JsonUnwrapped @JsonProperty("state") state: AutoScalingGroupState)

  case class AutoScalingGroupIdentity(autoScalingGroupName: String) extends AwsIdentity {
    @JsonIgnore val akkaIdentifier: String = s"AutoScalingGroup.$autoScalingGroupName"
  }

  type ServerGroupIdentity = AutoScalingGroupIdentity

  case class AutoScalingGroupState(createdTime: Long,
                                   VPCZoneIdentifier: String,
                                   launchConfigurationName: String,
                                   availabilityZones: Set[String],
                                   defaultCooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int,
                                   healthCheckType: String, loadBalancerNames: Set[String],
                                   maxSize: Int, minSize: Int, suspendedProcesses: Set[SuspendedProcess],
                                   terminationPolicies: Set[String],
                                   subnetType: Option[String], vpcName: Option[String]) {
    def forVpc(vpcName: Option[String]): AutoScalingGroupState = {
      val newLoadBalancerNames = loadBalancerNames.map(LoadBalancerIdentity(_).forVpc(vpcName).loadBalancerName)
      this.copy(loadBalancerNames = newLoadBalancerNames, vpcName = vpcName,
        subnetType = constructTargetSubnetType(subnetType, vpcName))
    }

    def withCapacity(size: Int): AutoScalingGroupState = {
      copy(minSize = 0, maxSize = 0, desiredCapacity = 0)
    }

    def populateVpcAttributes(vpcs: List[Vpc], subnets: List[Subnet]): AutoScalingGroupState = {
      val splitVpcZoneIdentifier = VPCZoneIdentifier.split(",")
      val asgState: Option[AutoScalingGroupState] = splitVpcZoneIdentifier.headOption.flatMap { subnetId =>
        val subnetOption = subnets.find(_.subnetId == subnetId)
        subnetOption.flatMap { subnet =>
          val vpcOption = vpcs.find(_.vpcId == subnet.vpcId)
          vpcOption.map { vpc =>
            copy(subnetType = Option(subnet.subnetType), vpcName = vpc.name)
          }
        }
      }
      asgState match {
        case Some(state) => state
        case None => this
      }
    }
  }

  case class SuspendedProcess(processName: String, suspensionReason: String)

  case class Subnet(subnetId: String, vpcId: String,
                    availabilityZone: String, availableIpAddressCount: Int, cidrBlock: String, defaultForAz: Boolean,
                    mapPublicIpOnLaunch: Boolean, state: String, tags: List[Tag]) {
    private val objectMapper = new ObjectMapper()
    private val immutableMetadataKey = "immutable_metadata"
    private val nameKey = "Name"
    private val purposeKey = "purpose"

    def subnetType: String = {
      tags.find(tag => nameKey.equalsIgnoreCase(tag.key)) match {
        case Some(subnetNameTag) =>
          val subnetName = subnetNameTag.value
          val parts = subnetName.split("\\.")
          s"${parts(1)} (${parts(0)})"
        case None =>
          tags.find(_.key == immutableMetadataKey) match {
            case Some(immutable_metadata) =>
              val keyValue = objectMapper.convertValue(immutable_metadata.value, classOf[Map[String, String]])
              keyValue(purposeKey)
            case None => ""
          }
      }
    }
  }

  case class Vpc(vpcId: String,
                 cidrBlock: String, dhcpOptionsId: String, instanceTenancy: String, isDefault: Boolean, state: String,
                 tags: List[Tag]) {
    def name: Option[String] = {
      val nameTagOption: Option[Tag] = tags.find(_.key == "Name")
      nameTagOption.map(_.value)
    }
  }

}
