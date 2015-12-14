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

import com.netflix.spinnaker.tide.actor.copy.{ClusterVpcMigrator, ClusterDependencies, ClusterDependencyCollector}
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation
import com.netflix.spinnaker.tide.model.Front50Service.PipelineState
import com.netflix.spinnaker.tide.model._
import org.junit.runner.RunWith
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{DiagrammedAssertions, GivenWhenThen, FlatSpec, FunSuite}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PipelineTest extends FlatSpec with GivenWhenThen with DiagrammedAssertions with TableDrivenPropertyChecks {

  behavior of "Pipeline"

  val singleDeploymentPipeline = PipelineState("deploy testApp", "testApp", parallel = false,
    List(Map(
      "enabled" -> true,
      "type" -> "jenkins",
      "master" -> "spinnaker",
      "job" -> "SPINNAKER-package-tide"
    )),
    List(
      Map(
        "type" -> "bake",
        "name" -> "Bake",
        "user" -> "cmccoy",
        "baseOs" -> "ubuntu",
        "baseLabel" -> "release",
        "vmType" -> "pv",
        "storeType" -> "ebs",
        "package" -> "testApp",
        "regions" -> List("us-west-1")
      ),
      Map(
        "type" -> "deploy",
        "name" -> "Deploy",
        "clusters" -> List(
          Map(
            "application" -> "testApp",
            "strategy" -> "redblack",
            "capacity" -> Map(
              "min" -> 1,
              "max" -> 1,
              "desired" -> 1
            ),
            "cooldown" -> 10,
            "healthCheckType" -> "EC2",
            "healthCheckGracePeriod" -> 600,
            "instanceMonitoring" -> true,
            "ebsOptimized" -> false,
            "iamRole" -> "BaseIAMRole",
            "terminationPolicies" -> List("Default"),
            "availabilityZones" -> Map(
              "us-west-1" -> List("us-west-1a", "us-west-1c")
            ),
            "keyPair" -> "keypair-a",
            "suspendedProcesses" -> Nil,
            "stack" -> "prestaging",
            "freeFormDetails" -> "",
            "loadBalancers" -> List("testApp-frontend"),
            "subnetType" -> "internal",
            "instanceType" -> "m3.large",
            "securityGroups" -> List("sg-1234", "sg-abcd"),
            "account" -> "prod",
            "provider" -> "aws"
          )
        )
      )
    )
  )

  it should "collect dependencies for a single deployment pipeline" in {
    Given("a pipeline with one deploy stage")
    val pipeline = singleDeploymentPipeline

    When("collection visitor is applied")
    val collector = ClusterDependencyCollector()
    val visitedPipeline = pipeline.applyVisitor(collector)

    Then("dependencies are collected")
    val expectedDependencies = List(
      ClusterDependencies("prod", "us-west-1", Option("internal"), Set("testApp-frontend"), Set("sg-1234", "sg-abcd"))
    )
    assert(visitedPipeline == pipeline)
    assert(collector.dependencies == expectedDependencies)
  }

  it should "migrate a single deployment pipeline" in {
    Given("a pipeline with one deploy stage")
    val pipeline = singleDeploymentPipeline

    When("vpc migrate visitor is applied")
    val migrator = ClusterVpcMigrator(Option("Main"), "vpc0", Map(AwsLocation("prod", "us-west-1") ->
      Map("sg-1234" -> "sg-5678", "sg-abcd" -> "sg-efgh")))
    val migratedPipeline = pipeline.applyVisitor(migrator)

    Then("dependencies are collected")
    val expectedPipeline = PipelineState("deploy testApp", "testApp", parallel = false,
      List(Map(
        "enabled" -> true,
        "type" -> "jenkins",
        "master" -> "spinnaker",
        "job" -> "SPINNAKER-package-tide"
      )),
      List(
        Map(
          "type" -> "bake",
          "name" -> "Bake",
          "user" -> "cmccoy",
          "baseOs" -> "ubuntu",
          "baseLabel" -> "release",
          "vmType" -> "pv",
          "storeType" -> "ebs",
          "package" -> "testApp",
          "regions" -> List("us-west-1")
        ),
        Map(
          "type" -> "deploy",
          "name" -> "Deploy",
          "clusters" -> List(
            Map(
              "application" -> "testApp",
              "strategy" -> "redblack",
              "capacity" -> Map(
                "min" -> 1,
                "max" -> 1,
                "desired" -> 1
              ),
              "cooldown" -> 10,
              "healthCheckType" -> "EC2",
              "healthCheckGracePeriod" -> 600,
              "instanceMonitoring" -> true,
              "ebsOptimized" -> false,
              "iamRole" -> "BaseIAMRole",
              "terminationPolicies" -> List("Default"),
              "availabilityZones" -> Map(
                "us-west-1" -> List("us-west-1a", "us-west-1c")
              ),
              "keyPair" -> "keypair-a",
              "suspendedProcesses" -> Nil,
              "stack" -> "prestaging",
              "freeFormDetails" -> "",
              "loadBalancers" -> Set("testApp-vpc0"),
              "subnetType" -> "internal (vpc0)",
              "instanceType" -> "m3.large",
              "securityGroups" -> Set("sg-5678", "sg-efgh"),
              "account" -> "prod",
              "provider" -> "aws"
            )
          )
        )
      )
    )
    assert(migratedPipeline == expectedPipeline)
  }

}
