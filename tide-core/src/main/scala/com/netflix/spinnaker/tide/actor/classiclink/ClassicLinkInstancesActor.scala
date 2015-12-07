package com.netflix.spinnaker.tide.actor.classiclink

import akka.actor.{ActorLogging, Actor, Props}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.{ClassicLinkSecurityGroupNames, InstancesNeedingClassicLinkAttached, GetInstancesNeedingClassicLinkAttached}
import com.netflix.spinnaker.tide.actor.polling.ClassicLinkInstanceIdPollingActor.LatestClassicLinkInstanceIds
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.ServerGroupPollingActor.NonclassicLinkedLaunchConfigEc2ClassicInstanceIds
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model.AkkaClustered
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

class ClassicLinkInstancesActor extends Actor with ActorLogging {

  var classicLinkSecurityGroupNames: Seq[String] = Nil
  var classicLinkSecurityGroupIds: Seq[String] = Nil
  var classicLinkVpcId: Option[String] = None
  var classicLinkInstanceIds: Option[Seq[String]] = None
  var nonclassicLinkedLaunchConfigEc2ClassicInstanceIds: Option[Seq[String]] = None

  override def receive: Receive = {
    case ClassicLinkSecurityGroupNames(_, names) =>
      classicLinkSecurityGroupNames = names

    case LatestVpcs(_, vpcs, _) =>
      classicLinkVpcId = vpcs.find(_.classicLinkEnabled).map(_.vpcId)

    case LatestSecurityGroupIdToNameMappings(_, securityGroupIdToName) =>
      val nameToId = securityGroupIdToName.map { case (id, identity) => identity.groupName -> id }
      classicLinkSecurityGroupIds = classicLinkSecurityGroupNames.map( name => nameToId(name))

    case LatestClassicLinkInstanceIds(_, instanceIds) =>
      classicLinkInstanceIds = Some(instanceIds)

    case NonclassicLinkedLaunchConfigEc2ClassicInstanceIds(_, instanceIds) =>
      nonclassicLinkedLaunchConfigEc2ClassicInstanceIds = Some(instanceIds)

    case GetInstancesNeedingClassicLinkAttached(_) =>
      (classicLinkVpcId, classicLinkInstanceIds, nonclassicLinkedLaunchConfigEc2ClassicInstanceIds) match {
        case (Some(vpcId), Some(attachedInstances), Some(allInstances)) =>
          log.info(s"****** GetInstancesNeedingClassicLinkAttached requirements met.")
          log.info(s"classicLinkVpcId - $classicLinkVpcId")
          log.info(s"classicLinkInstanceIds - $classicLinkInstanceIds")
          log.info(s"nonclassicLinkedLaunchConfigEc2ClassicInstanceIds - $nonclassicLinkedLaunchConfigEc2ClassicInstanceIds")
          log.info(s"classicLinkSecurityGroupIds - $classicLinkSecurityGroupIds")
          val unattachedInstances = allInstances.diff(attachedInstances)
          sender() ! InstancesNeedingClassicLinkAttached(vpcId, classicLinkSecurityGroupIds, unattachedInstances)
        case _ =>
          log.info(s"*!*!*! GetInstancesNeedingClassicLinkAttached requirements not met.")
          log.info(s"classicLinkVpcId - $classicLinkVpcId")
          log.info(s"classicLinkInstanceIds - $classicLinkInstanceIds")
          log.info(s"nonclassicLinkedLaunchConfigEc2ClassicInstanceIds - $nonclassicLinkedLaunchConfigEc2ClassicInstanceIds")
          log.info(s"classicLinkSecurityGroupIds - $classicLinkSecurityGroupIds")
      }

  }
}

sealed trait ClassicLinkInstanceProtocol extends Serializable

object ClassicLinkInstancesActor extends ClusteredActorObject {
  val props = Props[ClassicLinkInstancesActor]

  case class ClassicLinkSecurityGroupNames(location: AwsLocation, names: Seq[String]) extends ClassicLinkInstanceProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }

  case class GetInstancesNeedingClassicLinkAttached(location: AwsLocation) extends ClassicLinkInstanceProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
  case class InstancesNeedingClassicLinkAttached(classicLinkVpcId: String,
                                                 classicLinkSecurityGroupIds: Seq[String],
                                                 nonclassicLinkInstanceIds: Seq[String]) extends ClassicLinkInstanceProtocol {
    val nonclassicLinkInstanceCount = nonclassicLinkInstanceIds.size
  }

}