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

import akka.actor.{ActorLogging, Props}
import com.netflix.frigga.Names
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.model._
import CloudDriverActor.{CloudDriverResponse, GetTaskDetail}
import com.netflix.spinnaker.tide.model.CloudDriverService._
import com.netflix.spinnaker.tide.model.CloudDriverService.Listener
import retrofit.RestAdapter.LogLevel

class CloudDriverActor extends RetrofitServiceActor[CloudDriverService] with ActorLogging {

  var vpcs: Map[AwsLocation, Seq[Vpc]] = Map()

  def operational: Receive = {
    case AwsResourceProtocol(awsReference, event: UpsertSecurityGroup) =>
      val ref = awsReference.asInstanceOf[AwsReference[SecurityGroupIdentity]]
      val op = ConstructCloudDriverOperations.constructUpsertSecurityGroupOperation(ref, event.state, vpcs)
      val taskResult = service.submitTask(op.content())
      sender() ! CloudDriverResponse(service.getTaskDetail(taskResult.id))
      waitBetweenEventsToAvoidThrottling()

    case AwsResourceProtocol(awsReference, event: UpsertLoadBalancer) =>
      val ref = awsReference.asInstanceOf[AwsReference[LoadBalancerIdentity]]
      val op = ConstructCloudDriverOperations.constructUpsertLoadBalancerOperation(ref, event.state)
      val taskResult = service.submitTask(op.content())
      sender() ! CloudDriverResponse(service.getTaskDetail(taskResult.id))

    case AwsResourceProtocol(awsReference, event: CloneServerGroup) =>
      val ref = awsReference.asInstanceOf[AwsReference[ServerGroupIdentity]]
      val op = ConstructCloudDriverOperations.constructCloneServerGroupOperation(ref, event)
      val taskResult = service.submitTask(op.content())
      sender() ! CloudDriverResponse(service.getTaskDetail(taskResult.id))

    case AwsResourceProtocol(awsReference, event: AttachClassicLinkVpc) =>
      val ref = awsReference.asInstanceOf[AwsReference[InstanceIdentity]]
      val op = ConstructCloudDriverOperations.constructAttachClassicLinkVpcOperation(ref, event)
      val taskResult = service.submitTask(op.content())
      sender() ! CloudDriverResponse(service.getTaskDetail(taskResult.id))
      waitBetweenEventsToAvoidThrottling()

    case event: GetTaskDetail =>
      sender() ! CloudDriverResponse(service.getTaskDetail(event.id))

    case event: LatestVpcs =>
      vpcs += (event.location -> event.vpcs)

  }

  def waitBetweenEventsToAvoidThrottling() = {
    Thread.sleep(200)
  }
}

sealed trait CloudDriverProtocol extends Serializable

object CloudDriverActor extends SingletonActorObject {
  val props = Props[CloudDriverActor]

  case class CloudDriverInit(url: String)
    extends CloudDriverProtocol with RetrofitServiceInit[CloudDriverService] {
    override def logLevel = LogLevel.FULL
    override val serviceType: Class[CloudDriverService] = classOf[CloudDriverService]
  }

  case class CloudDriverResponse(taskDetail: TaskDetail) extends CloudDriverProtocol
  case class GetTaskDetail(id: String) extends CloudDriverProtocol

}

object ConstructCloudDriverOperations {

  def constructUpsertSecurityGroupOperation(awsReference: AwsReference[SecurityGroupIdentity],
                                            securityGroupState: SecurityGroupState,
                                            vpcs: Map[AwsLocation, Seq[Vpc]],
                                            ingressAppendOnly: Boolean = true): UpsertSecurityGroupOperation = {
    var securityGroupIngress: Set[SecurityGroupIngress] = Set()
    var ipIngress: Set[IpIngress] = Set()
    securityGroupState.ipPermissions.foreach { ipPermission =>
      ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
        val vpcId: Option[String] = userIdGroupPair.vpcName match {
          case Some(vpcName) =>
            val vpcsForLocationOption = vpcs.get(awsReference.location.copy(account = userIdGroupPair.account.name.get))
            vpcsForLocationOption.flatMap(_.find(_.name.contains(vpcName)).map(_.vpcId))
          case None => None
        }
        securityGroupIngress += SecurityGroupIngress.from(userIdGroupPair, ipPermission, vpcId)
      }
      ipPermission.ipRanges.foreach(ipIngress += IpIngress.from(_, ipPermission))
    }
    UpsertSecurityGroupOperation(awsReference.location.account,
      awsReference.location.region,
      awsReference.identity.vpcId.orNull,
      awsReference.identity.groupName,
      securityGroupState.description,
      securityGroupIngress,
      ipIngress,
      ingressAppendOnly)
  }

  def constructUpsertLoadBalancerOperation(awsReference: AwsReference[LoadBalancerIdentity],
                                           loadBalancer: LoadBalancerState): UpsertLoadBalancerOperation = {
    val healthCheck = loadBalancer.healthCheck
    val awsLocation = awsReference.location
    val availabilityZones: Map[String, Set[String]] = Map(
      awsLocation.region -> loadBalancer.availabilityZones
    )
    val listeners: Set[Listener] = loadBalancer.listenerDescriptions.map(Listener.from)
    UpsertLoadBalancerOperation(awsLocation.account, availabilityZones, loadBalancer.vpcId.orNull,
      awsReference.identity.loadBalancerName, loadBalancer.subnetType, loadBalancer.securityGroups, healthCheck.target,
      healthCheck.interval, healthCheck.timeout, healthCheck.unhealthyThreshold, healthCheck.healthyThreshold, listeners)
  }

  def constructCloneServerGroupOperation(source: AwsReference[ServerGroupIdentity],
                                         cloneServerGroup: CloneServerGroup): CloneServerGroupOperation = {
    val target = cloneServerGroup.target
    val autoScalingGroup = cloneServerGroup.autoScalingGroup
    val launchConfiguration = cloneServerGroup.launchConfiguration

    val availabilityZones: Map[String, Set[String]] = Map(
      target.location.region -> autoScalingGroup.availabilityZones
    )

    val capacity = Capacity(autoScalingGroup.minSize, autoScalingGroup.maxSize, autoScalingGroup.desiredCapacity)
    val names = Names.parseName(source.identity.autoScalingGroupName)

    val application = cloneServerGroup.application.getOrElse(names.getApp)
    val stack = cloneServerGroup.stack.getOrElse(names.getStack)
    val detail = cloneServerGroup.detail.getOrElse(names.getDetail)

    CloneServerGroupOperation(application, stack, detail,
      autoScalingGroup.subnetType, autoScalingGroup.vpcName, availabilityZones,
      target.location.account, launchConfiguration.securityGroups, autoScalingGroup.loadBalancerNames, capacity,
      launchConfiguration.iamInstanceProfile, launchConfiguration.keyName, launchConfiguration.imageId,
      launchConfiguration.instanceType, launchConfiguration.associatePublicIpAddress, launchConfiguration.ramdiskId,
      autoScalingGroup.terminationPolicies, autoScalingGroup.suspendedProcesses,
      launchConfiguration.spotPrice, autoScalingGroup.healthCheckType, autoScalingGroup.healthCheckGracePeriod,
      autoScalingGroup.defaultCooldown, launchConfiguration.isInstanceMonitoringEnabled,
      launchConfiguration.ebsOptimized, cloneServerGroup.startDisabled, Source.from(source))
  }

  def constructAttachClassicLinkVpcOperation(awsReference: AwsReference[InstanceIdentity],
                                             attachClassicLinkVpc: AttachClassicLinkVpc): AttachClassicLinkVpcOperation = {
    val awsLocation = awsReference.location
    AttachClassicLinkVpcOperation(awsLocation.account, awsLocation.region, awsReference.identity.instanceId,
      attachClassicLinkVpc.vpcId, attachClassicLinkVpc.securityGroupIds)
  }

}