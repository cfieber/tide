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

import AwsApi._
import retrofit.http.{GET, Headers}

trait EddaService {

  @Headers(Array("Accept: application/json"))
  @GET("/aws/securityGroups;_expand:(description,groupId,groupName,vpcId,ipPermissions:(fromPort,ipProtocol,ipRanges,toPort,userIdGroupPairs:(groupId,groupName)))")
  def securityGroups: Seq[SecurityGroup]

  @Headers(Array("Accept: application/json"))
  @GET("/aws/loadBalancers;_expand:(loadBalancerName,createdTime,scheme,securityGroups,VPCId,sourceSecurityGroup,subnets,availabilityZones,listenerDescriptions,healthCheck)")
  def loadBalancers: Seq[LoadBalancer]

  @Headers(Array("Accept: application/json"))
  @GET("/aws/launchConfigurations;_expand:(associatePublicIpAddress,blockDeviceMappings,classicLinkVPCId,classicLinkVPCSecurityGroups,createdTime,ebsOptimized,iamInstanceProfile,imageId,instanceMonitoring,instanceType,kernelId,keyName,launchConfigurationName,placementTenancy,ramdiskId,securityGroups,spotPrice)")
  def launchConfigurations: Seq[LaunchConfiguration]

  @Headers(Array("Accept: application/json"))
  @GET("/aws/autoScalingGroups;_expand:(VPCZoneIdentifier,autoScalingGroupName,availabilityZones,createdTime,defaultCooldown,desiredCapacity,enabledMetrics,healthCheckGracePeriod,healthCheckType,launchConfigurationName,loadBalancerNames,maxSize,minSize,placementGroup,status,suspendedProcesses,terminationPolicies)")
  def autoScalingGroups: Seq[AutoScalingGroup]

  @Headers(Array("Accept: application/json"))
  @GET("/aws/subnets;_expand:(availabilityZone,availableIpAddressCount,cidrBlock,defaultForAz,mapPublicIpOnLaunch,state,subnetId,tags:(key,value),vpcId)")
  def subnets: Seq[Subnet]

  @Headers(Array("Accept: application/json"))
  @GET("/aws/vpcs;_expand:(cidrBlock,dhcpOptionsId,instanceTenancy,isDefault,state,tags:(key,value),vpcId)")
  def vpcs: Seq[Vpc]

}

