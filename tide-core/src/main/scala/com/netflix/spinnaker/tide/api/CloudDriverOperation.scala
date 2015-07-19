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

package com.netflix.spinnaker.tide.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.netflix.frigga.Names
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor.CloneServerGroup


sealed trait CloudDriverOperation {
  def operationTypeName: String

  def content(): List[Map[String, CloudDriverOperation]] = {
    List(Map(operationTypeName -> this))
  }
}

case class UpsertSecurityGroupOperation(credentials: String, region: String, vpcId: String, name: String, description: String,
                               securityGroupIngress: Set[SecurityGroupIngress], ipIngress: Set[IpIngress]) extends CloudDriverOperation {
  val operationTypeName = "upsertSecurityGroupDescription"
}
object UpsertSecurityGroupOperation {
  def from(awsReference: AwsReference[SecurityGroupIdentity], securityGroupState: SecurityGroupState): UpsertSecurityGroupOperation = {
    var securityGroupIngress: Set[SecurityGroupIngress] = Set()
    var ipIngress: Set[IpIngress] = Set()
    securityGroupState.ipPermissions.foreach { ipPermission =>
      ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
        securityGroupIngress += SecurityGroupIngress.from(userIdGroupPair.groupName.get, ipPermission)
      }
      ipPermission.ipRanges.foreach(ipIngress += IpIngress.from(_, ipPermission))
    }
    UpsertSecurityGroupOperation(awsReference.location.account,
      awsReference.location.region,
      awsReference.identity.vpcId.orNull,
      awsReference.identity.groupName,
      securityGroupState.description,
      securityGroupIngress,
      ipIngress)
  }
}

case class SecurityGroupIngress(name: String, @JsonProperty("type") protocol: String, startPort: Int, endPort: Int)
object SecurityGroupIngress {
  def from(name: String, ipPermission: IpPermission): SecurityGroupIngress = {
    SecurityGroupIngress(name, ipPermission.ipProtocol, ipPermission.fromPort, ipPermission.toPort)
  }
}

case class IpIngress(cidr: String, @JsonProperty("type") protocol: String, startPort: Int, endPort: Int)
object IpIngress {
  def from(cidr: String, ipPermission: IpPermission): IpIngress = {
    IpIngress(cidr, ipPermission.ipProtocol, ipPermission.fromPort, ipPermission.toPort)
  }
}

case class UpsertLoadBalancerOperation(credentials: String, availabilityZones: Map[String, Set[String]], vpcId: String,
                                       name: String, subnetType: Option[String], securityGroups: Set[String],
                                       healthCheck: String, healthInterval: Int, healthTimeout: Int,
                                       unhealthyThreshold: Int, healthyThreshold: Int, listeners: Set[Listener]) extends CloudDriverOperation {
  val operationTypeName = "upsertAmazonLoadBalancerDescription"
}
object UpsertLoadBalancerOperation {
  def from(awsReference: AwsReference[LoadBalancerIdentity], loadBalancer: LoadBalancerState): UpsertLoadBalancerOperation = {
    val healthCheck = loadBalancer.healthCheck
    val awsLocation = awsReference.location
    val availabilityZones: Map[String, Set[String]] = Map(
      awsLocation.region -> loadBalancer.availabilityZones
    )
    val listeners: Set[Listener] = loadBalancer.listenerDescriptions.map(Listener.from(_))
    UpsertLoadBalancerOperation(awsLocation.account, availabilityZones, loadBalancer.vpcId.orNull,
      awsReference.identity.loadBalancerName, loadBalancer.subnetType, loadBalancer.securityGroups, healthCheck.target,
      healthCheck.interval, healthCheck.timeout, healthCheck.unhealthyThreshold, healthCheck.healthyThreshold, listeners)
  }
}

case class Listener(sslCertificateId: String, externalPort: Int, internalPort: Int, externalProtocol: String,
                    internalProtocol: String)
object Listener {
  def from(listenerDescription: ListenerDescription): Listener = {
    Listener(
      listenerDescription.listener.SSLCertificateId.orNull,
      listenerDescription.listener.loadBalancerPort,
      listenerDescription.listener.instancePort,
      listenerDescription.listener.protocol,
      listenerDescription.listener.instanceProtocol
    )
  }
}

case class CloneServerGroupOperation(application: String, stack: String, freeFormDetails: String,
                                     subnetType: Option[String], vpcName: Option[String],
                                     availabilityZones: Map[String, Set[String]], credentials: String,
                                     securityGroups: Set[String], loadBalancers: Set[String], capacity: Capacity,
                                     iamRole: String, keyPair: String, amiName: String, instanceType: String,
                                     associatePublicIpAddress: Option[Boolean], ramdiskId: String,
                                     terminationPolicies: Set[String], suspendedProcesses: Set[String],
                                     spotPrice: Option[String], healthCheckType: String, healthCheckGracePeriod: Int,
                                     cooldown: Int, instanceMonitoring: Boolean, ebsOptimized: Boolean,
                                     startDisabled: Boolean, source: Source) extends CloudDriverOperation {
  val operationTypeName = "copyLastAsgDescription"
}
object CloneServerGroupOperation {
  def from(awsReference: AwsReference[ServerGroupIdentity], cloneServerGroup: CloneServerGroup): CloneServerGroupOperation = {
    val awsLocation = awsReference.location
    val autoScalingGroup = cloneServerGroup.autoScalingGroup
    val launchConfiguration = cloneServerGroup.launchConfiguration

    val availabilityZones: Map[String, Set[String]] = Map(
      awsReference.location.region -> autoScalingGroup.availabilityZones
    )

    val capacity = Capacity(autoScalingGroup.minSize, autoScalingGroup.maxSize, autoScalingGroup.desiredCapacity)
    val names = Names.parseName(awsReference.identity.autoScalingGroupName)

    val application = cloneServerGroup.application.getOrElse(names.getApp)
    val stack = cloneServerGroup.stack.getOrElse(names.getStack)
    val detail = cloneServerGroup.detail.getOrElse(names.getDetail)

    val isInstanceMonitoringEnabled: Boolean = Option(launchConfiguration.instanceMonitoring) match {
      case None => false
      case Some(instanceMonitoring) => instanceMonitoring.enabled
    }

    CloneServerGroupOperation(application, stack, detail,
      autoScalingGroup.subnetType, autoScalingGroup.vpcName, availabilityZones,
      awsLocation.account, launchConfiguration.securityGroups, autoScalingGroup.loadBalancerNames, capacity,
      launchConfiguration.iamInstanceProfile, launchConfiguration.keyName, launchConfiguration.imageId,
      launchConfiguration.instanceType, launchConfiguration.associatePublicIpAddress, launchConfiguration.ramdiskId,
      autoScalingGroup.terminationPolicies, autoScalingGroup.suspendedProcesses.map(_.processName),
      launchConfiguration.spotPrice, autoScalingGroup.healthCheckType, autoScalingGroup.healthCheckGracePeriod,
      autoScalingGroup.defaultCooldown, isInstanceMonitoringEnabled,
      launchConfiguration.ebsOptimized, cloneServerGroup.startDisabled, Source.from(awsReference))
  }
}

case class Capacity(min: Int, max: Int, desired: Int)

case class Source(account: String, region: String, asgName: String)
object Source {
  def from(awsReference: AwsReference[ServerGroupIdentity]): Source = {
    Source(awsReference.location.account, awsReference.location.region, awsReference.identity.autoScalingGroupName)
  }
}

object CloudDriverOperation {


}
