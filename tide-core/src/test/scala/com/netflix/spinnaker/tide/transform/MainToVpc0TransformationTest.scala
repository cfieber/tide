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

package com.netflix.spinnaker.tide.transform

import com.netflix.spinnaker.tide.model.AwsApi
import AwsApi.{UserIdGroupPairs, IpPermission}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{DiagrammedAssertions, GivenWhenThen, FlatSpec}

@RunWith(classOf[JUnitRunner])
class MainToVpc0TransformationTest extends FlatSpec with GivenWhenThen with DiagrammedAssertions with TableDrivenPropertyChecks {

  behavior of "MainToVpc0Transformation"

  val mainToVpc0Transformation = new MainToVpc0Transformation()

  it should "translate IP permissions" in {
    Given("IP permissions")
    val ipPermissions: Set[IpPermission] = Set(
      IpPermission(7001, 7001, "TCP", Set("1.0.0.0/32", "1.0.0.1/32"), Set()),
      IpPermission(7001, 7002, "TCP", Set("1.0.1.0/32", "1.0.1.1/32"), Set(UserIdGroupPairs(Option("sg-123"), Option("testSg")))),
      IpPermission(7001, 7003, "TCP", Set("1.0.2.0/32", "10.9.1.1/32", "100.9.1.1/32", "1.0.2.1/32"), Set()),
      IpPermission(7001, 7004, "TCP", Set("10.9.2.1/32", "100.9.2.1/32"), Set()),
      IpPermission(7001, 7005, "TCP", Set("10.9.3.1/32", "100.9.3.1/32"), Set(UserIdGroupPairs(Option("sg-123"), Option("testSg")))),
      IpPermission(7001, 7006, "TCP", Set("184.72.105.251/32"), Set()),
      IpPermission(7001, 7007, "TCP", Set("184.72.105.251/32", "107.21.35.88/32", "54.244.37.231/32"), Set()),
      IpPermission(7001, 7008, "TCP", Set("184.72.105.251/32", "10.9.1.1/32", "107.21.35.88/32", "1.0.2.1/32", "54.244.37.231/32"), Set())
    )

    When("translated")
    val translatedIpPermissions = mainToVpc0Transformation.translateIpPermissions(ipPermissions)

    Then("10. and 100. are removed, Nat IPs are mapped, and the rest is left alone")
    val expectedIpPermissions = Set(
      IpPermission(7001, 7001, "TCP", Set("1.0.0.0/32", "1.0.0.1/32"), Set()),
      IpPermission(7001, 7002, "TCP", Set("1.0.1.0/32", "1.0.1.1/32"), Set(UserIdGroupPairs(Option("sg-123"), Option("testSg")))),
      IpPermission(7001, 7003, "TCP", Set("1.0.2.0/32", "1.0.2.1/32"), Set()),
      IpPermission(7001, 7005, "TCP", Set(), Set(UserIdGroupPairs(Option("sg-123"), Option("testSg")))),
      IpPermission(7001, 7006, "TCP", Set("184.72.105.251/32", "54.165.127.252/30"), Set()),
      IpPermission(7001, 7007, "TCP", Set("184.72.105.251/32", "107.21.35.88/32", "54.244.37.231/32", "54.165.127.252/30", "54.218.127.176/30"), Set()),
      IpPermission(7001, 7008, "TCP", Set("184.72.105.251/32", "107.21.35.88/32", "54.244.37.231/32", "54.165.127.252/30", "1.0.2.1/32", "54.218.127.176/30"), Set())
    )
    assert(translatedIpPermissions == expectedIpPermissions)
  }

}
