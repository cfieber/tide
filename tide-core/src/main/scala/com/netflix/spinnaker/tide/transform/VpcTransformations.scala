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

import com.netflix.spinnaker.tide.model.AwsApi.{AwsReference, SecurityGroupIdentity, SecurityGroupState, IpPermission}


class VpcTransformations {
  def getVpcTransformation(sourceVpcName: Option[String], targetVpcName: Option[String]): VpcTransformation = {
    (sourceVpcName, targetVpcName) match {
      // TODO: This should clearly be configurable for handling other migrations.
      case (Some(source), Some(target)) if source.equalsIgnoreCase("Main") && target.equalsIgnoreCase("vpc0") =>
        new MainToVpc0Transformation()
      case other => new NoOpVpcTransformation()
    }
  }
}

trait VpcTransformation {
  def translateIpPermissions(reference: AwsReference[SecurityGroupIdentity], securityGroupState: SecurityGroupState): Set[IpPermission]
  def log: Seq[String]
}

class NoOpVpcTransformation extends VpcTransformation {
  def translateIpPermissions(reference: AwsReference[SecurityGroupIdentity], securityGroupState: SecurityGroupState): Set[IpPermission] = {
    securityGroupState.ipPermissions
  }
  override def log: Seq[String] = Nil
}

