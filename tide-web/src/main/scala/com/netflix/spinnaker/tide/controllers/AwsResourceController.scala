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

package com.netflix.spinnaker.tide.controllers

import akka.actor.{Props, ActorSystem, ActorRef}
import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.CloudDriverActor.CloudDriverResponse
import com.netflix.spinnaker.tide.actor.aws.DeepCopyDirector.{DeepCopyOptions, Target}
import com.netflix.spinnaker.tide.actor.aws._
import com.wordnik.swagger.annotations.{ApiOperation, Api}
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMethod._
import org.springframework.web.bind.annotation._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

@Api(value = "/resource", description = "Operations on cloud resources")
@RequestMapping(value = Array("/resource"))
@RestController
class AwsResourceController @Autowired()(private val clusterSharding: ClusterSharding,
                                           private val awsResource: AwsResourceActor.Ref) {

  implicit val timeout = Timeout(5 seconds)

  def deepCopyDirector: ActorRef = {
    clusterSharding.shardRegion(DeepCopyDirector.typeName)
  }

  @RequestMapping(value = Array("/securityGroup/{account}/{region}/{name}"), method = Array(GET))
  def getSecurityGroup(@PathVariable("account") account: String,
                       @PathVariable("region") region: String,
                       @PathVariable("name") name: String,
                       @RequestParam(value = "vpcId", required = false) vpcId: String): SecurityGroupDetails = {
    val reference = AwsReference(AwsLocation(account, region), SecurityGroupIdentity(name, Option(vpcId)))
    retrieveSecurityGroup(reference)
  }

  def retrieveSecurityGroup(reference: AwsReference[SecurityGroupIdentity]): SecurityGroupDetails = {
    val event = AwsResourceProtocol(reference, GetSecurityGroup())
    val future = (awsResource ? event).mapTo[SecurityGroupDetails]
    val securityGroupDetails = Await.result(future, timeout.duration)
    securityGroupDetails
  }

  @RequestMapping(value = Array("/securityGroup/{account}/{region}"), method = Array(POST))
  def setSecurityGroup(@PathVariable("account") account: String,
                       @PathVariable("region") region: String,
                       @RequestBody securityGroup: SecurityGroup): SecurityGroupDetails = {
    val reference = AwsReference(AwsLocation(account, region), securityGroup.identity)
    awsResource ! AwsResourceProtocol(reference, UpsertSecurityGroup(securityGroup.state))
    retrieveSecurityGroup(reference)
  }


  @RequestMapping(value = Array("/loadBalancer/{account}/{region}/{name}"), method = Array(GET))
  def getLoadBalancer(@PathVariable("account") account: String,
                       @PathVariable("region") region: String,
                       @PathVariable("name") name: String): LoadBalancerDetails = {
    val reference = AwsReference(AwsLocation(account, region), LoadBalancerIdentity(name))
    retrieveLoadBalancer(reference)
  }

  def retrieveLoadBalancer(reference: AwsReference[LoadBalancerIdentity]): LoadBalancerDetails = {
    val event = AwsResourceProtocol(reference, GetLoadBalancer())
    val future = (awsResource ? event).mapTo[LoadBalancerDetails]
    val loadBalancerDetails = Await.result(future, timeout.duration)
    loadBalancerDetails
  }

  @RequestMapping(value = Array("/loadBalancer/{account}/{region}"), method = Array(POST))
  def setLoadBalancer(@PathVariable("account") account: String,
                       @PathVariable("region") region: String,
                       @RequestBody loadBalancer: LoadBalancer): LoadBalancerDetails = {
    val reference = AwsReference(AwsLocation(account, region), loadBalancer.identity)
    awsResource ! AwsResourceProtocol(reference, UpsertLoadBalancer(loadBalancer.state))
    retrieveLoadBalancer(reference)
  }


  @RequestMapping(value = Array("/serverGroup/{account}/{region}/{name}"), method = Array(GET))
  def getServerGroup(@PathVariable("account") account: String,
                      @PathVariable("region") region: String,
                      @PathVariable("name") name: String): ServerGroupDetails = {
    val reference = AwsReference(AwsLocation(account, region), AutoScalingGroupIdentity(name))
    retrieveServerGroup(reference)
  }

  def retrieveServerGroup(reference: AwsReference[ServerGroupIdentity]): ServerGroupDetails = {
    val event = AwsResourceProtocol(reference, GetServerGroup())
    val future = (awsResource ? event).mapTo[ServerGroupDetails]
    val serverGroupDetails = Await.result(future, timeout.duration)
    serverGroupDetails
  }

  @RequestMapping(value = Array("/serverGroup/{account}/{region}/{name}/clone"), method = Array(POST))
  def cloneServerGroup(@PathVariable("account") account: String,
                     @PathVariable("region") region: String,
                     @PathVariable("name") name: String,
                     @RequestBody cloneServerGroup: CloneServerGroup) = {
    val reference = AwsReference(AwsLocation(account, region), AutoScalingGroupIdentity(name))
    val future = (awsResource ? AwsResourceProtocol(reference, cloneServerGroup)).mapTo[CloudDriverResponse]
    val cloudDriverResponse = Await.result(future, timeout.duration)
    cloudDriverResponse.taskDetail
  }

  @ApiOperation(value = "Copies the server group to the target along with dependencies.",
    notes = "Cloud resource dependencies will be copied as well if they do not exist (security groups, load balancers, scaling policies). Returns the task id.")
  @RequestMapping(value = Array("/serverGroup/{account}/{region}/{name}/deepCopy"), method = Array(POST))
  def deepCopyServerGroup(@PathVariable("account") account: String,
                              @PathVariable("region") region: String,
                              @PathVariable("name") name: String,
                              @RequestBody target: Target) = {
    val reference = AwsReference(AwsLocation(account, region), AutoScalingGroupIdentity(name))
    val deepCopyOptions = DeepCopyOptions(reference, target)
    deepCopyDirector ! deepCopyOptions
    s"${deepCopyOptions.akkaIdentifier}."
  }

}

