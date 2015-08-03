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

sealed trait SpinnakerApi

case class SearchResponse(totalMatches: Int, pageNumber: Int, pageSize: Int, platform: String, query: String,
                          results: List[SearchResults]) extends SpinnakerApi

case class SearchResults(account: String, region: String, instanceId: String, @JsonProperty("type") objectType: String,
                         application: String, cluster: String, serverGroup: String, provider: String)

case class ClusterDetailResponse(name: String, @JsonProperty("type") cloudProviderType: String, accountName: String,
                                 serverGroups: List[ServerGroup], loadBalancers: List[Map[String, Any]]) extends SpinnakerApi

sealed trait CloudObject
case class ServerGroup(name: String, launchConfigName: String, vpcId: String,
                       @JsonProperty("type") cloudProviderType: String, region: String, zones: List[String],
                       health: List[Any], image: Map[String, Any], asg: Map[String, Any],
                       buildInfo: Map[String, Any], launchConfig: Map[String, Any],
                       instances: List[Instance])
case class Instance(subnetId: String, virtualizationType: String, amiLaunchIndex: Int, sourceDestCheck: Boolean,
                    isHealthy: Boolean, instanceId: String, vpcId: String, hypervisor: String, rootDeviceName: String,
                    architecture: String, ebsOptimized: Boolean, imageId: String, stateTransitionReason: String,
                    clientToken: String, instanceType: String, keyName: String, publicDnsName: String,
                    privateIpAddress: String, rootDeviceType: String, launchTime: Long, name: String,
                    privateDnsName: String, monitoring: Map[String, Any], iamInstanceProfile: Map[String, Any],
                    state: Map[String, Any], tags: List[Map[String, Any]],
                    networkInterfaces: List[Map[String, Any]], securityGroups: List[Map[String, Any]],
                    health: List[Map[String, Any]], blockDeviceMappings: List[Map[String, Any]],
                    productCodes: List[Any], placement: Map[String, Any]) extends CloudObject
