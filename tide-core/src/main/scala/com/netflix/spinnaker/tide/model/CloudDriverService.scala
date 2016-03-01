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

import com.fasterxml.jackson.annotation.JsonProperty
import com.netflix.spinnaker.tide.model.CloudDriverService.{CloudDriverOperation, ClusterDetailResponse, TaskReference, TaskDetail}
import AwsApi._
import retrofit.http._

trait CloudDriverService {

  @Headers(Array("Accept: application/json"))
  @GET("/task/{taskId}")
  def getTaskDetail(@Path("taskId") taskId: String): TaskDetail

  @Headers(Array("Accept: application/json"))
  @POST("/ops")
  def submitTask(@Body cloudDriverOperations: Seq[Map[String, CloudDriverOperation]]): TaskReference

  @Headers(Array("Accept: application/json"))
  @GET("/applications/{application}/clusters/{account}/{cluster}")
  def getClusterDetail(
                        @Path("application") application: String,
                        @Path("account") account: String,
                        @Path("cluster") cluster: String): Seq[ClusterDetailResponse]
}

object CloudDriverService {

  case class TaskReference(id: String,
                           error: String,
                           errors: Seq[String],
                           status: String)

  case class TaskDetail(id: String,
                        startTimeMs: Long,
                        status: Status,
                        history: Seq[LogMessage],
                        resultObjects: Seq[Map[String, Any]]) {
    def getCreatedServerGroups: Seq[String] = {
      if (!status.completed) {
        return Nil
      }
      val resultName = "serverGroupNames"
      var newServerGroupNames: List[String] = Nil
      val resultMapOption: Option[Map[String, Any]] = resultObjects.find(_.contains(resultName))
      resultMapOption.foreach { resultMap =>
        val resultOption = resultMap.get(resultName)
        resultOption.foreach { result =>
          val resultList = result.asInstanceOf[Seq[String]]
          val resultParts = resultList.head.split(":")
          val serverGroupName: String = resultParts(1)
          newServerGroupNames ::= serverGroupName
        }
      }
      newServerGroupNames
    }
  }

  case class Status(phase: String,
                    status: String,
                    completed: Boolean,
                    failed: Boolean)

  case class LogMessage(status: String,
                        phase: String)


  case class ClusterDetailResponse(name: String, @JsonProperty("type") cloudProviderType: String, accountName: String,
                                   serverGroups: Seq[ServerGroup], loadBalancers: Seq[Map[String, Any]])

  case class ServerGroup(name: String, launchConfigName: String, vpcId: String,
                         @JsonProperty("type") cloudProviderType: String, region: String, zones: Seq[String],
                         health: Seq[Any], image: Map[String, Any], asg: Map[String, Any],
                         buildInfo: Map[String, Any], launchConfig: Map[String, Any],
                         instances: Seq[Instance])
  case class Instance(subnetId: String, virtualizationType: String, amiLaunchIndex: Int, sourceDestCheck: Boolean,
                      isHealthy: Boolean, instanceId: String, vpcId: String, hypervisor: String, rootDeviceName: String,
                      architecture: String, ebsOptimized: Boolean, imageId: String, stateTransitionReason: String,
                      clientToken: String, instanceType: String, keyName: String, publicDnsName: String,
                      privateIpAddress: String, rootDeviceType: String, launchTime: Long, name: String,
                      privateDnsName: String, monitoring: Map[String, Any], iamInstanceProfile: Map[String, Any],
                      state: Map[String, Any], tags: Seq[Map[String, Any]],
                      networkInterfaces: Seq[Map[String, Any]], securityGroups: Seq[Map[String, Any]],
                      health: Seq[Map[String, Any]], blockDeviceMappings: Seq[Map[String, Any]],
                      productCodes: Seq[Any], placement: Map[String, Any])


  sealed trait CloudDriverOperation {
    def operationTypeName: String

    def content(): Seq[Map[String, CloudDriverOperation]] = {
      List(Map(operationTypeName -> this))
    }
  }

  case class UpsertSecurityGroupOperation(credentials: String, region: String, vpcId: String, name: String, description: String,
                                          securityGroupIngress: Set[SecurityGroupIngress], ipIngress: Set[IpIngress],
                                          ingressAppendOnly: Boolean = true) extends CloudDriverOperation {
    val operationTypeName = "upsertSecurityGroupDescription"
  }

  case class SecurityGroupIngress(name: String, @JsonProperty("type") protocol: String, startPort: Option[Int], endPort: Option[Int])
  object SecurityGroupIngress {
    def from(name: String, ipPermission: IpPermission): SecurityGroupIngress = {
      SecurityGroupIngress(name, ipPermission.ipProtocol, ipPermission.fromPort, ipPermission.toPort)
    }
  }

  case class IpIngress(cidr: String, @JsonProperty("type") protocol: String, startPort: Option[Int], endPort: Option[Int])
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

  case class AttachClassicLinkVpcOperation(credentials: String, region: String, instanceId: String, vpcId: String,
                                  securityGroupIds: Seq[String]) extends CloudDriverOperation {
    val operationTypeName = "attachClassicLinkVpcDescription"
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

  case class Capacity(min: Int, max: Int, desired: Int)

  case class Source(account: String, region: String, asgName: String)
  object Source {
    def from(awsReference: AwsReference[ServerGroupIdentity]): Source = {
      Source(awsReference.location.account, awsReference.location.region, awsReference.identity.autoScalingGroupName)
    }
  }

}

