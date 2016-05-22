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

package com.netflix.spinnaker.tide


import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor
import com.netflix.spinnaker.tide.model.AwsApi
import AwsApi.{AccountKeyMapping, AwsLocation, VpcLocation}
import DependencyCopyActor.DependencyCopyTask
import scala.beans.BeanProperty

object WebModel {

  case class VpcDefinition(@BeanProperty account: String, @BeanProperty region: String, @BeanProperty vpcName: String, @BeanProperty keyName: String) {
    def toVpcLocation = {
      VpcLocation(AwsLocation(account, region), Option(vpcName), Option(keyName))
    }
  }
  case class DependencyCopyDefinition(@BeanProperty source: VpcDefinition,
                                @BeanProperty target: VpcDefinition,
                                @BeanProperty securityGroupNames: Set[String],
                                @BeanProperty loadBalancerNames: Set[String]) {
    def toDependencyCopyTask = {
      DependencyCopyTask(source.toVpcLocation, target.toVpcLocation, securityGroupNames, loadBalancerNames)
    }
  }

  case class PipelineVpcMigrateDefinition(@BeanProperty sourceVpcName: Option[String],
                                          @BeanProperty targetVpcName: String,
                                          @BeanProperty accountMapping: Set[PipelineVpcAccountMapping]) {
    def toAccountKeyMapping = {
      accountMapping.map(mapping => mapping.source -> AccountKeyMapping(mapping.target, mapping.keyName))
        .toMap
    }
  }

  case class PipelineVpcAccountMapping(@BeanProperty source: String,
                                       @BeanProperty target: String,
                                       @BeanProperty keyName: String)

}
