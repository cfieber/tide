package com.netflix.spinnaker.tide.actor.classiclink

import akka.actor.{ActorLogging, Actor, Props}
import akka.persistence.{RecoveryFailure, RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.ClusteredActorObject
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.{ClassicLinkSecurityGroupNames, InstancesNeedingClassicLinkAttached, GetInstancesNeedingClassicLinkAttached}
import com.netflix.spinnaker.tide.actor.polling.ClassicLinkInstanceIdPollingActor.LatestClassicLinkInstanceIds
import com.netflix.spinnaker.tide.actor.polling.SecurityGroupPollingActor.LatestSecurityGroupIdToNameMappings
import com.netflix.spinnaker.tide.actor.polling.ServerGroupPollingActor.NonclassicLinkedLaunchConfigEc2ClassicInstanceIds
import com.netflix.spinnaker.tide.actor.polling.VpcPollingActor.LatestVpcs
import com.netflix.spinnaker.tide.model.AkkaClustered
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

class ClassicLinkInstancesActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var classicLinkSecurityGroupNames: Seq[String] = Nil
  var classicLinkSecurityGroupIds: Seq[String] = Nil
  var classicLinkVpcId: Option[String] = None
  var classicLinkInstanceIds: Option[Seq[String]] = None
  var nonclassicLinkedLaunchConfigEc2ClassicInstanceIds: Option[Seq[String]] = None

  override def receiveCommand: Receive = {
    case msg: ClassicLinkSecurityGroupNames =>
      persist(msg) { names =>
        updateState(names)
      }

    case LatestVpcs(_, vpcs, _) =>
      classicLinkVpcId = vpcs.find(_.classicLinkEnabled).map(_.vpcId)

    case LatestSecurityGroupIdToNameMappings(_, securityGroupIdToName) =>
      val nameToId = securityGroupIdToName.map { case (id, identity) => identity.groupName -> id }
      classicLinkSecurityGroupIds = classicLinkSecurityGroupNames.map( name => nameToId(name))

    case LatestClassicLinkInstanceIds(_, instanceIds) =>
      classicLinkInstanceIds = Some(instanceIds)

    case NonclassicLinkedLaunchConfigEc2ClassicInstanceIds(_, instanceIds) =>
      nonclassicLinkedLaunchConfigEc2ClassicInstanceIds = Some(instanceIds)

    case GetInstancesNeedingClassicLinkAttached(location, limitOption) =>
      (classicLinkVpcId, classicLinkInstanceIds, nonclassicLinkedLaunchConfigEc2ClassicInstanceIds) match {
        case (Some(vpcId), Some(attachedInstances), Some(allInstances)) if classicLinkSecurityGroupIds.nonEmpty =>
          val unattachedInstances = allInstances.diff(attachedInstances)
          val instanceIdsToAttach = limitOption match {
            case Some(limit) => util.Random.shuffle(unattachedInstances) take limit
            case None => unattachedInstances
          }
          sender() ! InstancesNeedingClassicLinkAttached(vpcId, classicLinkSecurityGroupIds, instanceIdsToAttach)
        case _ =>
          log.info(s"""!**** GetInstancesNeedingClassicLinkAttached requirements not met in $location.
          classicLinkVpcId - $classicLinkVpcId
          classicLinkInstanceIds - ${classicLinkInstanceIds.isDefined}
          nonclassicLinkedLaunchConfigEc2ClassicInstanceIds - ${nonclassicLinkedLaunchConfigEc2ClassicInstanceIds.isDefined}
          classicLinkSecurityGroupNames - $classicLinkSecurityGroupNames
          classicLinkSecurityGroupIds - $classicLinkSecurityGroupIds""")
      }

  }

  override def receiveRecover: Receive = {
    case msg: RecoveryFailure => log.error(msg.cause, msg.cause.toString)
    case event: RecoveryCompleted =>
    case event =>
      updateState(event)
  }

  def updateState(event: Any) = {
    event match {
      case msg: ClassicLinkSecurityGroupNames =>
        classicLinkSecurityGroupNames = msg.names
      case _ =>
    }
  }
}

sealed trait ClassicLinkInstanceProtocol extends Serializable

object ClassicLinkInstancesActor extends ClusteredActorObject {
  val props = Props[ClassicLinkInstancesActor]

  case class ClassicLinkSecurityGroupNames(location: AwsLocation, names: Seq[String]) extends ClassicLinkInstanceProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }

  case class GetInstancesNeedingClassicLinkAttached(location: AwsLocation, limit: Option[Integer] = None) extends ClassicLinkInstanceProtocol with AkkaClustered {
    override def akkaIdentifier: String = location.akkaIdentifier
  }
  case class InstancesNeedingClassicLinkAttached(classicLinkVpcId: String,
                                                 classicLinkSecurityGroupIds: Seq[String],
                                                 nonclassicLinkInstanceIds: Seq[String]) extends ClassicLinkInstanceProtocol {
    val nonclassicLinkInstanceCount = nonclassicLinkInstanceIds.size
  }

}