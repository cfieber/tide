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

import com.fasterxml.jackson.annotation.{JsonProperty, JsonIgnore, JsonUnwrapped}
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.frigga.autoscaling.AutoScalingGroupNameBuilder
import com.netflix.spinnaker.tide.actor.task.TaskActor.MutationDetails

sealed trait AwsProtocol extends Serializable

object AwsApi {

  def constructTargetSubnetType(sourceSubnetType: String, targetVpcName: Option[String]): Option[String] = {
    val cleanSubnetType = sourceSubnetType.replaceAll("DEPRECATED_", "").replaceAll("-elb", "").replaceAll("-ec2", "")
    targetVpcName match {
      case Some(vpcName) => Option(s"${cleanSubnetType.split(" ").head} ($vpcName)")
      case _ => None
    }
  }

  def getVpcNameFromSubnetType(subnetTypeOption: Option[String]): Option[String] = {
    subnetTypeOption match {
      case Some(subnetType) =>
        val pattern = """(.*) \((.+)\)""".r
        pattern.findFirstMatchIn(subnetType) match {
          case Some(m) if m.groupCount == 2 => Option(m.group(2))
          case _ => Option("Main")
        }
      case None => None
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

  case class AwsLocation(account: String, region: String) extends AkkaClustered {
    @JsonIgnore override def akkaIdentifier = s"$account.$region"
  }

  case class VpcLocation(@JsonUnwrapped location: AwsLocation, vpcName: Option[String])

  trait AwsIdentity extends AkkaClustered

  case class AwsReference[T <: AwsIdentity](location: AwsLocation, identity: T) extends AkkaClustered {
    @JsonIgnore override def akkaIdentifier = s"${location.akkaIdentifier}.${identity.akkaIdentifier}"
  }

  case class CreateAwsResource(awsReference: AwsReference[_ <: AwsIdentity],
                               referencedBy: Option[AwsReference[_]], objectToCreate: Option[Any]= None) extends MutationDetails

  case class SecurityGroup(groupId: String,
                           @JsonUnwrapped @JsonProperty("name") identity: SecurityGroupIdentity,
                           @JsonUnwrapped @JsonProperty("state") state: SecurityGroupState) extends AwsProtocol

  case class LoadBalancer(@JsonUnwrapped @JsonProperty("identity") identity: LoadBalancerIdentity,
                          @JsonUnwrapped @JsonProperty("state") state: LoadBalancerState) extends AwsProtocol

  case class LaunchConfiguration(@JsonUnwrapped @JsonProperty("identity") identity: LaunchConfigurationIdentity,
                                 @JsonUnwrapped @JsonProperty("state") state: LaunchConfigurationState) extends AwsProtocol

  case class AutoScalingGroup(@JsonUnwrapped @JsonProperty("identity") identity: AutoScalingGroupIdentity,
                              @JsonUnwrapped @JsonProperty("state") state: AutoScalingGroupState,
                              instances: Seq[Instance]) extends AwsProtocol

  case class Tag(key: String, value: String)

  case class SecurityGroupIdentity(groupName: String, vpcId: Option[String] = None) extends AwsIdentity {
    @JsonIgnore def akkaIdentifier: String = s"SecurityGroup.$groupName.${vpcId.getOrElse("")}"
    def dropLegacySuffix: SecurityGroupIdentity = {
      val newGroupName = groupName match {
        case s if s.endsWith("-vpc") => s"${s.dropRight("-vpc".length)}"
        case s => groupName
      }
      SecurityGroupIdentity(newGroupName, vpcId)
    }
  }

  case class SecurityGroupState(description: String, ipPermissions: Set[IpPermission])  extends AwsProtocol {
    def ensureSecurityGroupNameOnIngressRules(securityGroupIdToName: Map[String, SecurityGroupIdentity]): SecurityGroupState = {
      val newIpPermissions = ipPermissions.map { ipPermission =>
        val newUserIdGroupPairs = ipPermission.userIdGroupPairs.map {
          case pair @ UserIdGroupPairs(_, Some(groupName), _, _) => pair
          case pair @ UserIdGroupPairs(Some(groupId), None, _, _) =>
            pair.copy(groupName = securityGroupIdToName.get(groupId).map(_.groupName))
        }
        ipPermission.copy(userIdGroupPairs = newUserIdGroupPairs)
      }
      copy(ipPermissions = newIpPermissions)
    }

    def removeLegacySuffixesFromSecurityGroupIngressRules(): SecurityGroupState = {
      val newIpPermissions = ipPermissions.map { ipPermission =>
        val newUserIdGroupPairs = ipPermission.userIdGroupPairs.map { userGroupPair =>
          val newGroupName: Option[String] = userGroupPair.groupName.map(SecurityGroupIdentity(_).dropLegacySuffix.groupName)
          UserIdGroupPairs(None, newGroupName, userGroupPair.account, userGroupPair.vpcName)
        }
        ipPermission.copy(userIdGroupPairs = newUserIdGroupPairs)
      }
      copy(ipPermissions = newIpPermissions)
    }
  }

  case class IpPermission(fromPort: Option[Int],
                          toPort: Option[Int],
                          ipProtocol: String,
                          ipRanges: Set[String],
                          userIdGroupPairs: Set[UserIdGroupPairs])

  case class UserIdGroupPairs(groupId: Option[String], groupName: Option[String], account: AccountIdentifier, vpcName: Option[String])

  case class LoadBalancerIdentity(loadBalancerName: String) extends AwsIdentity {
    @JsonIgnore def akkaIdentifier: String = s"LoadBalancer.$loadBalancerName"

    @JsonIgnore def forVpc(sourceVpcNameOption: Option[String], targetVpcNameOption: Option[String]): LoadBalancerIdentity = {
      val sourceVpcNameRemoved = sourceVpcNameOption match {
        case Some(vpcName) if loadBalancerName.endsWith(s"-$vpcName") =>
          loadBalancerName.dropRight(s"-$vpcName".length)
        case None if loadBalancerName.endsWith("-classic") =>
          loadBalancerName.dropRight("-classic".length)
        case _ => loadBalancerName
      }
      val legacySuffixesRemoved = sourceVpcNameRemoved match {
        case s if s.endsWith("-frontend") =>
          s.dropRight("-frontend".length)
        case s if s.endsWith("-vpc") =>
          s.dropRight("-vpc".length)
        case s => s
      }

      val truncateAsLastResort = new LoadBalancerNameShortener().
        shorten(legacySuffixesRemoved, 32 - (targetVpcNameOption.getOrElse("").length + 1))

      val targetVpcNameAdded = targetVpcNameOption match {
        case Some(vpcName) => s"$truncateAsLastResort-$vpcName"
        case _ => s"$truncateAsLastResort-classic"
      }
      LoadBalancerIdentity(targetVpcNameAdded)
    }

    @JsonIgnore def isConsistentWithVpc(vpcNameOption: Option[String]): Boolean = {
      vpcNameOption match {
        case None => loadBalancerName.endsWith("-classic")
        case Some(vpcName) => loadBalancerName.endsWith(s"-$vpcName")
      }
    }
  }

  case class LoadBalancerState(@JsonProperty("VPCId") vpcId: Option[String],
                               availabilityZones: Set[String],
                               healthCheck: HealthCheck, listenerDescriptions: Set[ListenerDescription],
                               scheme: String, securityGroups: Set[String],
                               sourceSecurityGroup: ElbSourceSecurityGroup, subnets: Set[String],
                               subnetType: Option[String])  extends AwsProtocol {

    def forVpc(vpcName: Option[String], vpcId: Option[String]): LoadBalancerState = {
      val securityGroups: Set[String] = if (vpcId.isDefined) this.securityGroups else Set()
      this.copy(vpcId = vpcId, subnetType = constructTargetSubnetType(subnetType.getOrElse("external"), vpcName),
        securityGroups = securityGroups)
    }

    def convertToSecurityGroupNames(securityGroupIdToName: Map[String, SecurityGroupIdentity]): LoadBalancerState = {
      copy(securityGroups = normalizeSecurityGroupNames(securityGroups, securityGroupIdToName))
    }

    def removeLegacySuffixesFromSecurityGroups(): LoadBalancerState = {
      val newGroupNames = securityGroups.map(SecurityGroupIdentity(_).dropLegacySuffix.groupName)
      copy(securityGroups = newGroupNames)
    }

    def populateVpcAttributes(vpcs: Seq[Vpc], subnetDetails: Seq[Subnet]): LoadBalancerState = {
      val loadBalancerState: Option[LoadBalancerState] = subnets.headOption.flatMap { subnetId =>
        val subnetOption = subnetDetails.find(_.subnetId == subnetId)
        subnetOption.map { subnet =>
          copy(subnetType = Option(subnet.name), vpcId = Option(subnet.vpcId))
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

  case class LaunchConfigurationIdentity(launchConfigurationName: String) extends AwsIdentity {
    @JsonIgnore def akkaIdentifier: String = s"LaunchConfiguration.$launchConfigurationName"
  }

  case class LaunchConfigurationState(associatePublicIpAddress: Option[Boolean],
                                      ebsOptimized: Boolean, iamInstanceProfile: String, imageId: String,
                                      isInstanceMonitoringEnabled: Boolean, instanceType: String, kernelId: String,
                                      keyName: String, ramdiskId: String,
                                      securityGroups: Set[String], spotPrice: Option[String],
                                      classicLinkVPCId: Option[String]) extends AwsProtocol {
    def convertToSecurityGroupNames(securityGroupIdToName: Map[String, SecurityGroupIdentity]): LaunchConfigurationState = {
      copy(securityGroups = normalizeSecurityGroupNames(securityGroups, securityGroupIdToName))
    }

    def dropSecurityGroupNameLegacySuffixes: LaunchConfigurationState = {
      val newSecurityGroups = securityGroups.map(SecurityGroupIdentity(_).dropLegacySuffix.groupName)
      copy(securityGroups = newSecurityGroups)
    }
  }

  case class InstanceMonitoring(enabled: Boolean)

  case class AutoScalingGroupIdentity(autoScalingGroupName: String) extends AwsIdentity {
    @JsonIgnore def akkaIdentifier: String = s"AutoScalingGroup.$autoScalingGroupName"
    def nextGroup: AutoScalingGroupIdentity = {
      AutoScalingGroupIdentity(AutoScalingGroupNameBuilder.buildNextGroupName(autoScalingGroupName))
    }
  }

  type ServerGroupIdentity = AutoScalingGroupIdentity

  case class InstanceIdentity(instanceId: String) extends AwsIdentity {
    @JsonIgnore def akkaIdentifier: String = s"Instance.$instanceId"
  }

  case class AutoScalingGroupState(VPCZoneIdentifier: String,
                                   launchConfigurationName: String,
                                   availabilityZones: Set[String],
                                   defaultCooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int,
                                   healthCheckType: String, loadBalancerNames: Set[String],
                                   maxSize: Int, minSize: Int, suspendedProcesses: Set[String],
                                   terminationPolicies: Set[String],
                                   subnetType: Option[String], vpcName: Option[String]) extends AwsProtocol {
    def forVpc(sourceVpcName: Option[String], targetVpcName: Option[String]): AutoScalingGroupState = {
      val newLoadBalancerNames = loadBalancerNames.map(LoadBalancerIdentity(_).
        forVpc(sourceVpcName, targetVpcName).loadBalancerName)
      this.copy(loadBalancerNames = newLoadBalancerNames, vpcName = targetVpcName,
        subnetType = constructTargetSubnetType(subnetType.getOrElse("internal"), targetVpcName),
        VPCZoneIdentifier = ""
      )
    }

    def withCapacity(size: Int): AutoScalingGroupState = {
      copy(minSize = 0, maxSize = 0, desiredCapacity = 0)
    }

    def populateVpcAttributes(vpcs: Seq[Vpc], subnets: Seq[Subnet]): AutoScalingGroupState = {
      val splitVpcZoneIdentifier = VPCZoneIdentifier.split(",")
      val asgState: Option[AutoScalingGroupState] = splitVpcZoneIdentifier.headOption.flatMap { subnetId =>
        val subnetOption = subnets.find(_.subnetId == subnetId)
        subnetOption.flatMap { subnet =>
          val vpcOption = vpcs.find(_.vpcId == subnet.vpcId)
          vpcOption.map { vpc =>
            copy(subnetType = Option(subnet.name), vpcName = vpc.name)
          }
        }
      }
      asgState match {
        case Some(state) => state
        case None => this
      }
    }
  }

  case class Subnet(subnetId: String, vpcId: String, name: String) extends AwsProtocol

  case class Vpc(vpcId: String, name: Option[String], classicLinkEnabled: Boolean) extends AwsProtocol

  case class VpcClassicLink(vpcId: String, classicLinkEnabled: Boolean)

  case class Instance(instanceId: String, lifecycleState: String, healthStatus: String, launchConfigurationName: String)

  case class AccountIdentifier(id: String, name: Option[String])
}
