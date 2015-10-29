package com.netflix.spinnaker.tide.actor.aws

import akka.actor.{Props, Actor}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.aws.ClassicLinkInstancesActor.{InstancesNeedingClassicLinkAttached, GetInstancesNeedingClassicLinkAttached}
import com.netflix.spinnaker.tide.actor.polling.ClassicLinkInstanceIdPollingActor.LatestClassicLinkInstanceIds
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.ServerGroupPollingActor.NonclassicLinkedLaunchConfigEc2ClassicInstanceIds
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model.AkkaClustered
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

class ClassicLinkInstancesActor extends Actor {

  var classicLinkVpcId: Option[String] = None
  var classicLinkSecurityGroupIds: Option[Seq[String]] = None
  var classicLinkInstanceIds: Option[Seq[String]] = None
  var nonclassicLinkedLaunchConfigEc2ClassicInstanceIds: Option[Seq[String]] = None

  override def receive: Receive = {
    case LatestVpcs(location, vpcs) =>
      classicLinkVpcId = vpcs.find(_.classicLinkEnabled).map(_.vpcId)

    case LatestSecurityGroupIdToNameMappings(location, securityGroupIdToName) =>
      val nameToId = securityGroupIdToName.map { case (id, identity) => identity.groupName -> id }
      val classicLinkSecurityGroups = Seq("nf-classiclink")
      classicLinkSecurityGroupIds = Some(classicLinkSecurityGroups.flatMap(nameToId.get))

    case LatestClassicLinkInstanceIds(location, instanceIds) =>
      classicLinkInstanceIds = Some(instanceIds)

    case NonclassicLinkedLaunchConfigEc2ClassicInstanceIds(location, instanceIds) =>
      nonclassicLinkedLaunchConfigEc2ClassicInstanceIds = Some(instanceIds)

    case GetInstancesNeedingClassicLinkAttached(location) =>
      (classicLinkVpcId, classicLinkSecurityGroupIds, classicLinkInstanceIds, nonclassicLinkedLaunchConfigEc2ClassicInstanceIds) match {
        case (Some(vpcId), Some(securityGroups), Some(attachedInstances), Some(allInstances)) =>
          val unattachedInstances = allInstances.diff(attachedInstances)
          sender() ! InstancesNeedingClassicLinkAttached(vpcId, securityGroups, unattachedInstances)
        case _ =>
      }

  }
}

sealed trait ClassicLinkInstanceProtocol

object ClassicLinkInstancesActor extends ClusteredActorObject {
  val props = Props[ClassicLinkInstancesActor]

  case class GetInstancesNeedingClassicLinkAttached(location: AwsLocation) extends ClassicLinkInstanceProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
  case class InstancesNeedingClassicLinkAttached(classicLinkVpcId: String,
                                                 classicLinkSecurityGroupIds: Seq[String],
                                                 nonclassicLinkInstanceIds: Seq[String]) extends ClassicLinkInstanceProtocol {
    val nonclassicLinkInstanceCount = nonclassicLinkInstanceIds.size
  }

}