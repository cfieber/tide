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

import com.netflix.spinnaker.tide.actor.aws.AwsApi.{AwsLocation, IpPermission}

import scala.util.matching.Regex

/**
 * This logic is specific to Main to VPC0 migration. Once this logic is verified and a clean abstraction is understood,
 * it needs to be removed and put into config.
 *
 * TODO: Commits with this in it should be removed from the history before the project is open sourced.
 */
class MainToVpc0Transformation extends VpcTransformation{

  def translateIpPermissions(ipPermissions: Set[IpPermission]): Set[IpPermission] = {
    val removePatterns: Set[Regex] = Set("10\\..*".r, "100\\..*".r)
    val mainVpcNatIpsToLocation: Map[String, AwsLocation] = Map(
      "184.72.105.251/32"  -> AwsLocation("test", "us-east-1"),
      "107.21.35.88/32"    -> AwsLocation("test", "us-east-1"),
      "54.208.3.88/32"     -> AwsLocation("test", "us-east-1"),
      "50.18.195.185/32"   -> AwsLocation("test", "us-west-1"),
      "50.18.197.36/32"    -> AwsLocation("test", "us-west-1"),
      "54.244.38.98/32"    -> AwsLocation("test", "us-west-2"),
      "50.112.128.24/32"   -> AwsLocation("test", "us-west-2"),
      "54.244.37.237/32"   -> AwsLocation("test", "us-west-2"),
      "176.34.128.2/32"    -> AwsLocation("test", "eu-west-1"),
      "54.229.21.178/32"   -> AwsLocation("test", "eu-west-1"),
      "54.229.29.64/32"    -> AwsLocation("test", "eu-west-1"),

      "184.72.121.248/32"  -> AwsLocation("prod", "us-east-1"),
      "54.208.14.27/32"    -> AwsLocation("prod", "us-east-1"),
      "107.21.36.116/32"   -> AwsLocation("prod", "us-east-1"),
      "50.18.198.246/32"   -> AwsLocation("prod", "us-west-1"),
      "54.215.136.196/32"  -> AwsLocation("prod", "us-west-1"),
      "54.244.37.231/32"   -> AwsLocation("prod", "us-west-2"),
      "54.244.38.242/32"   -> AwsLocation("prod", "us-west-2"),
      "54.244.39.59/32"    -> AwsLocation("prod", "us-west-2"),
      "176.34.128.217/32"  -> AwsLocation("prod", "eu-west-1"),
      "54.229.29.76/32"    -> AwsLocation("prod", "eu-west-1"),
      "54.229.31.54/32"    -> AwsLocation("prod", "eu-west-1")
    )

    val vpc0VpcNatIpsByLocation: Map[AwsLocation, String] = Map(
      AwsLocation("test", "us-east-1")      -> "54.165.127.252/30",
      AwsLocation("test", "us-west-1")      -> "54.219.190.220/30",
      AwsLocation("test", "us-west-2")      -> "54.213.255.128/30",
      AwsLocation("test", "eu-west-1")      -> "54.77.255.220/30",
      AwsLocation("test", "ap-northeast-1") -> "54.64.94.220/30",

      AwsLocation("prod", "us-east-1")      -> "54.173.62.180/30",
      AwsLocation("prod", "us-west-1")      -> "54.153.127.208/30",
      AwsLocation("prod", "us-west-2")      -> "54.218.127.176/30",
      AwsLocation("prod", "eu-west-1")      -> "54.171.187.236/30",
      AwsLocation("prod", "ap-northeast-1") -> "54.64.43.56/30"
    )

    val convertedIpPermissions = ipPermissions.map { ipPermission =>
      var newIpRanges: Set[String] = Set()
      ipPermission.ipRanges.foreach { ipRange =>
        val matchRemovePatterns = removePatterns.find(_.findFirstIn(ipRange).isDefined)
        matchRemovePatterns match {
          case Some(it) =>
            Nil
          case None =>
            newIpRanges += ipRange
        }
        mainVpcNatIpsToLocation.get(ipRange) match {
          case Some(locationForNatIp) =>
            newIpRanges += vpc0VpcNatIpsByLocation(locationForNatIp)
          case None =>
            Nil
        }
      }
      ipPermission.copy(ipRanges = newIpRanges)
    }
    convertedIpPermissions.filter { ipPermission =>
      ipPermission.userIdGroupPairs.nonEmpty || ipPermission.ipRanges.nonEmpty
    }
  }

}
