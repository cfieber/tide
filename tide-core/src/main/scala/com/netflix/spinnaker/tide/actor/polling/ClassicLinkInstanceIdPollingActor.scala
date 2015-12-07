package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.amazonaws.services.ec2.model.DescribeClassicLinkInstancesRequest
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.{EddaPollingProtocol, EddaPoll}
import com.netflix.spinnaker.tide.actor.polling.ClassicLinkInstanceIdPollingActor.LatestClassicLinkInstanceIds
import com.netflix.spinnaker.tide.model.AkkaClustered
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation
import scala.collection.JavaConversions._

class ClassicLinkInstanceIdPollingActor extends PollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, ClassicLinkInstanceIdPollingActor)

  val clusterSharding: ClusterSharding = ClusterSharding.get(context.system)

  override def receive: Receive = {
    case msg: EddaPoll =>
      val location = msg.location
      pollScheduler.scheduleNextPoll(msg)
      val amazonEc2 = getAwsServiceProvider(location).getAmazonEC2
      val instanceIds = retrieveAll{ nextToken =>
        val result = amazonEc2.describeClassicLinkInstances(new DescribeClassicLinkInstancesRequest().withNextToken(nextToken))
        (result.getInstances.map(_.getInstanceId), Option(result.getNextToken))
      }
      log.info(s"***** LatestClassicLinkInstanceIds - $location - $instanceIds")
      clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName) ! LatestClassicLinkInstanceIds(location, instanceIds)
  }

}

object ClassicLinkInstanceIdPollingActor extends PollingActorObject {
  val props = Props[ClassicLinkInstanceIdPollingActor]
  case class LatestClassicLinkInstanceIds(location: AwsLocation, resources: Seq[String]) extends EddaPollingProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
}
