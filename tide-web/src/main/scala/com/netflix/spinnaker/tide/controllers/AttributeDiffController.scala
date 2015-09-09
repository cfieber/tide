package com.netflix.spinnaker.tide.controllers

import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.ServerGroupActor.{ServerGroupComparableAttributes, GetServerGroupDiff}
import com.netflix.spinnaker.tide.actor.comparison.{AttributeDiffActor, AttributeDiff}
import com.netflix.spinnaker.tide.model.AwsApi._
import com.wordnik.swagger.annotations.{ApiOperation, Api}
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.{RestController, RequestBody, PathVariable, RequestMapping}
import org.springframework.web.bind.annotation.RequestMethod._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

@Api(value = "/diff", description = "Comparisons of cloud resources")
@RequestMapping(value = Array("/diff"))
@RestController
class AttributeDiffController@Autowired()(private val clusterSharding: ClusterSharding) {

  implicit val timeout = Timeout(5 seconds)
  def attributeDiffCluster = clusterSharding.shardRegion(AttributeDiffActor.typeName)

  @ApiOperation(value = "Compares server groups in the cluster.",
    notes = "The result shows all server groups across region and VPC, but within an account." +
      " In 'allIdentifiers' you will find the identifying coordinates of each sever group." +
      " In 'attributeGroups' we list 'commonAttributes' shared by a group of 'identifiers' (sorted from most to least common).")
  @RequestMapping(value = Array("/cluster/{account}/{clusterName}/"), method = Array(GET))
  def serverGroupDiff(@PathVariable("account") account: String,
                      @PathVariable("clusterName") clusterName: String): AttributeDiff[AwsReference[ServerGroupIdentity]] = {
    val future = (attributeDiffCluster ? GetServerGroupDiff(account, clusterName)).mapTo[AttributeDiff[AwsReference[ServerGroupIdentity]]]
    Await.result(future, timeout.duration)
  }

  @ApiOperation(value = "Compares specified server group attributes with the existing server groups in the cluster.",
    notes = "The specified server group displays with the name 'NEW'." +
      " The result shows all server groups across region and VPC, but within an account." +
      " In 'allIdentifiers' you will find the identifying coordinates of each sever group." +
      " In 'attributeGroups' we list 'commonAttributes' shared by a group of 'identifiers' (sorted from most to least common).")
  @RequestMapping(value = Array("/cluster/{account}/{clusterName}/"), method = Array(POST))
  def serverGroupDiffCompare( @PathVariable("account") account: String,
                              @PathVariable("clusterName") clusterName: String,
                              @RequestBody serverGroup: ServerGroupComparableAttributes): AttributeDiff[AwsReference[ServerGroupIdentity]] = {
    val future = (attributeDiffCluster ? GetServerGroupDiff(account, clusterName)).mapTo[AttributeDiff[AwsReference[ServerGroupIdentity]]]
    val diff = Await.result(future, timeout.duration)
    diff.compareResource(AwsReference(AwsLocation("NONE", "NONE"), AutoScalingGroupIdentity("NEW")), serverGroup)
  }
}
