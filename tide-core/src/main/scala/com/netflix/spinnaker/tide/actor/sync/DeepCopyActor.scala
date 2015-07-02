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

package com.netflix.spinnaker.tide.actor.sync

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.sync.AwsApi._
import com.netflix.spinnaker.tide.api.CloudDriverService.TaskDetail
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

class DeepCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler

  var awsResource: ActorRef = _
  var sourceReference: AwsReference[AutoScalingGroupIdentity] = _
  var sourceVpcId: Option[String] = _
  var target: Target = _
  var targetVpc: Option[Vpc] = _

  def vpcCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(VpcPollingActor.typeName)
  }

  def deepCopyDirector: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(DeepCopyDirector.typeName)
  }

  var serverGroupState: Option[ServerGroupLatestState] = None

  var history: List[Any] = Nil
  var pendingTasks: Map[String, TaskDetail] = Map()
  var completedTasks: Map[String, TaskDetail] = Map()
  var failedTasks: Map[String, TaskDetail] = Map()
  var resourcesToRequired: Set[AwsIdentity] = Set()
  var resourcesNeeded: Set[AwsIdentity] = Set()
  var loadBalancerNameTargetToSource: Map[String, String] = Map()
  var cloneServerGroupTaskId: Option[String] = None

  case class VpcLocation(location: AwsLocation, vpcId: Option[String])

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def getVpcByName(name: String, awsLocation: AwsLocation): Option[Vpc] = {
    val future = (vpcCluster ? GetVpcs(awsLocation.account, awsLocation.region)).mapTo[List[Vpc]]
    val vpcs = Await.result(future, timeout.duration)
    vpcs.find { vpc =>
      vpc.tags.exists(tag => tag.key == "Name" && tag.value == name)
    }
  }

  override def receiveCommand: Receive = {
    case event: GetDeepCopyStatus =>
      sender ! DeepCopyStatus(history, pendingTasks, completedTasks, failedTasks, resourcesToRequired, resourcesNeeded,
        loadBalancerNameTargetToSource, cloneServerGroupTaskId)

    case event: DeepCopyStart =>
      val target = event.options.target
      val vpcOption = getVpcByName(target.vpcName, target.location)
      persist(event.copy(targetVpc = vpcOption)) { e =>
        vpcOption match {
          case Some(vpc) =>
            updateState(e)
            awsResource ! AwsResourceProtocol(sourceReference, GetServerGroup(), None)
          case None =>
            val message = s"Cannot find VPC '${target.vpcName}' in ${target.location.account} ${target.location.region}"
            self ! DeepCopyFailure(event.options, message)
        }
      }

    case event: DeepCopyFailure =>
      persist(event) { it =>
        updateState(it)
        deepCopyDirector ! it
      }

    case event: DeepCopyComplete =>
      persist(event) { it =>
        updateState(it)
        deepCopyDirector ! it
      }

    case event: DeepCopyContinue =>
      awsResource = event.awsResource
      awsResource ! AwsResourceProtocol(sourceReference, GetServerGroup(), None)

    case event: Requires =>
      if (!resourcesToRequired.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
        }
      }
      val request = event match {
        case Requires(identity: LoadBalancerIdentity) =>
          AwsResourceProtocol(AwsReference(target.location, identity), GetLoadBalancer(), None)
        case Requires(identity: SecurityGroupIdentity) =>
          AwsResourceProtocol(AwsReference(target.location, identity.copy(vpcId = targetVpc.map(_.vpcId))), GetSecurityGroup(), None)
      }
      awsResource ! request

    case event: Found =>
      val startServerGroupCloning = () =>
        if (resourcesToRequired.diff(resourcesNeeded).isEmpty && !cloneServerGroupTaskId.isDefined) {
          self ! StartServerGroupCloning()
        }
      if (!resourcesNeeded.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
          startServerGroupCloning()
        }
      } else {
        startServerGroupCloning()
      }

    case event: ServerGroupDetails =>
      event.latestState.foreach { latestState =>
        sourceVpcId = latestState.autoScalingGroup.vpcId
        serverGroupState = Option(latestState)
        val securityGroups = latestState.launchConfiguration.securityGroups
        val loadBalancerNames = latestState.autoScalingGroup.loadBalancerNames
        securityGroups.foreach(it => self ! Requires(SecurityGroupIdentity(it)))
        loadBalancerNames.foreach(it => self ! Requires(LoadBalancerIdentity(covertToTargetLoadBalancerName(it))))
        if (securityGroups.isEmpty && loadBalancerNames.isEmpty) {
          self ! StartServerGroupCloning()
        }
      }

    case event: StartServerGroupCloning =>
      if (!cloneServerGroupTaskId.isDefined) {
        persist(event) { e =>
          updateState(e)
          val autoScalingGroup = serverGroupState.get.autoScalingGroup
          val newLoadBalancerNames = autoScalingGroup.loadBalancerNames.map(covertToTargetLoadBalancerName)
          val newAutoScalingGroup = autoScalingGroup.copy(
            loadBalancerNames = newLoadBalancerNames, vpcId = targetVpc.map(_.vpcId), minSize = 0, maxSize = 0,
            desiredCapacity = 0, subnetType = constructTargetSubnetType(autoScalingGroup.subnetType))
          val cloneServerGroup = AwsResourceProtocol(sourceReference,
            CloneServerGroup(newAutoScalingGroup, serverGroupState.get.launchConfiguration, startDisabled = true))
          val future = (awsResource ? cloneServerGroup).mapTo[CloudDriverResponse]
          val cloudDriverResponse = Await.result(future, timeout.duration)
          val cloudDriverReference = cloudDriverResponse.cloudDriverReference
          val id = cloudDriverResponse.taskDetail.id
          cloneServerGroupTaskId = Option(id)
          scheduler.scheduleOnce(15 seconds, cloudDriverReference, GetTaskDetail(id))
          self ! CloneServerGroupTask(cloudDriverResponse)
        }
      }

    case event: CloneServerGroupTask =>
      persist(event) { e =>
        updateState(e)
      }

    case event: LoadBalancerDetails =>
      val name = event.awsReference.identity.loadBalancerName
      event.latestState match {
        case None =>
          if (loadBalancerNameTargetToSource.contains(name)) {
            awsResource ! AwsResourceProtocol(AwsReference(sourceReference.location,
              LoadBalancerIdentity(covertToSourceLoadBalancerName(name))), GetLoadBalancer(), None)
          }
        case Some(latestState) =>
          if (loadBalancerNameTargetToSource.contains(name)) {
            self ! Found(LoadBalancerIdentity(name))
          } else {
            latestState.state.securityGroups.foreach(it => self ! Requires(SecurityGroupIdentity(it)))
            val newSubnetType = constructTargetSubnetType(latestState.state.subnetType)
            val upsert = UpsertLoadBalancer(latestState.state.copy(vpcId = targetVpc.map(_.vpcId), subnetType = newSubnetType), overwrite = false)
            val referenceToUpsert = AwsReference(target.location, LoadBalancerIdentity(covertToTargetLoadBalancerName(name)))
            persist(AwsResourceProtocol(referenceToUpsert, upsert)) {
              updateState _
              awsResource ! _
            }
          }
      }

    case event: SecurityGroupDetails =>
      val name = event.awsReference.identity.groupName
      val Source = VpcLocation(sourceReference.location, sourceVpcId)
      val Target = VpcLocation(target.location, targetVpc.map(_.vpcId))
      VpcLocation(event.awsReference.location, event.awsReference.identity.vpcId) match {
        case Target =>
          event.latestState match {
            case None =>
              awsResource ! AwsResourceProtocol(AwsReference(sourceReference.location, SecurityGroupIdentity(name, sourceVpcId)), GetSecurityGroup(), None)
            case Some(latestState) =>
              self ! Found(SecurityGroupIdentity(name))
          }
        case Source =>
          event.latestState match {
            case None =>
            case Some(latestState) =>
              latestState.state.ipPermissions.foreach { ipPermission =>
                ipPermission.userIdGroupPairs.foreach { userIdGroupPair =>
                  self ! Requires(SecurityGroupIdentity(userIdGroupPair.groupName.get))
                }
              }
              val upsert = UpsertSecurityGroup(latestState.state, overwrite = false)
              val referenceToUpsert = AwsReference(target.location, SecurityGroupIdentity(name, targetVpc.map(_.vpcId)))
              persist(AwsResourceProtocol(referenceToUpsert, upsert)) {
                updateState _
                awsResource ! _
              }
          }
      }

    case event: CloudDriverResponse =>
      persist(event) { it =>
        updateState(it)
        if (!event.taskDetail.status.completed && !event.taskDetail.status.failed) {
          scheduler.scheduleOnce(15 seconds, event.cloudDriverReference, GetTaskDetail(event.taskDetail.id))
        }
        cloneServerGroupTaskId.foreach { id =>
          if (it.taskDetail.id == id) {
            val options = DeepCopyOptions(sourceReference, target)
            if (it.taskDetail.status.completed) {
              self ! DeepCopyComplete(options)
            }
            if (it.taskDetail.status.failed) {
              self ! DeepCopyFailure(options, it.taskDetail.status.status)
            }
          }
        }
      }

  }

  def updateState(event: Any) = {
    event match {
      case event: DeepCopyStart =>
        awsResource = event.awsResource
        sourceReference = event.options.source
        target = event.options.target
        targetVpc = event.targetVpc
        cloneServerGroupTaskId = None
        history = s"Start deep copy of ${sourceReference.akkaIdentifier}" :: history
      case event: DeepCopyFailure =>
        history = s"Failure: ${event.message}" :: history
      case event: DeepCopyComplete =>
        history = s"Deep copy complete." :: history
      case Requires(identity: AwsIdentity) =>
        history = s"Requires ${identity.akkaIdentifier}" :: history
        resourcesToRequired += identity
      case Found(identity: AwsIdentity) =>
        history = s"Found ${identity.akkaIdentifier}" :: history
        resourcesNeeded += identity
      case AwsResourceProtocol(reference, upsert: UpsertLoadBalancer, _) =>
        history = s"Creating Load Balancer ${reference.akkaIdentifier}" :: history
      case AwsResourceProtocol(reference, upsert: UpsertSecurityGroup, _) =>
        history = s"Creating Security Group ${reference.akkaIdentifier}" :: history
      case AwsResourceProtocol(reference, event: CloneServerGroup, _) =>
        history = s"Cloning Server Group from ${reference.akkaIdentifier}" :: history
      case event: CloneServerGroupTask =>
        cloneServerGroupTaskId = Option(event.response.taskDetail.id)
      case event: TaskDetail =>
        pendingTasks += (event.id -> event)
        if (event.status.completed) {
          completedTasks += (event.id -> event)
        }
        if (event.status.failed) {
          failedTasks += (event.id -> event)
        }
        history = s"Task ${event.id} ${event.status}" :: history
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }

  def startCopying(): Unit = {
    awsResource ! AwsResourceProtocol(sourceReference, GetServerGroup(), None)
    resourcesToRequired.diff(resourcesNeeded).foreach {
      case identity: SecurityGroupIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(target.location, identity.copy(vpcId = targetVpc.map(_.vpcId))),
          GetSecurityGroup(), None)
      case identity: LoadBalancerIdentity =>
        awsResource ! AwsResourceProtocol(AwsReference(target.location, identity), GetLoadBalancer(), None)
    }
  }

  def constructTargetSubnetType(sourceSubnetTypeOption: Option[String]): Option[String] = {
    sourceSubnetTypeOption.map{ subnetType =>
      val cleanSubnetType = subnetType.replaceAll("DEPRECATED_", "").replaceAll("-elb", "").replaceAll("-ec2", "")
      s"${cleanSubnetType.split(" ").head} (${target.vpcName})"
    }
  }

  def covertToTargetLoadBalancerName(sourceName: String): String = {
    val targetName = sourceName match {
      case s if s.endsWith("-frontend") =>
        s"${s.dropRight("-frontend".length)}-${target.vpcName}"
      case s if sourceName.endsWith("-vpc") =>
        s"${sourceName.dropRight("-vpc".length)}-${target.vpcName}"
      case s if sourceName.endsWith(s"-${target.vpcName}") =>
        sourceName
      case s =>
        s"$sourceName-${target.vpcName}"
    }
    loadBalancerNameTargetToSource += (targetName -> sourceName)
    targetName
  }

  def covertToSourceLoadBalancerName(targetName: String): String = {
    loadBalancerNameTargetToSource(targetName)
  }

}

case class Requires(awsIdentity: AwsIdentity)
case class Found(awsIdentity: AwsIdentity)
case class StartServerGroupCloning()
case class DeepCopyComplete(options: DeepCopyOptions)
case class DeepCopyFailure(options: DeepCopyOptions, message: String)
case class CloneServerGroupTask(response: CloudDriverResponse)
case class DeepCopyStatus(history: List[Any], pendingTasks: Map[String, TaskDetail],
                          completedTasks: Map[String, TaskDetail], failedTasks: Map[String, TaskDetail],
                          resourcesToRequired: Set[AwsIdentity], resourcesNeeded: Set[AwsIdentity],
                          loadBalancerNameTargetToSource: Map[String, String], cloneServerGroupTaskId: Option[String])

case class DeepCopyStart(awsResource: ActorRef, options: DeepCopyOptions, targetVpc: Option[Vpc] = None) extends AkkaClustered {
  val akkaIdentifier: String = options.akkaIdentifier
}
case class DeepCopyContinue(awsResource: ActorRef, options: DeepCopyOptions, targetVpc: Option[Vpc] = None) extends AkkaClustered {
  val akkaIdentifier: String = options.akkaIdentifier
}

case class GetDeepCopyStatus(id: String) extends AkkaClustered {
  val akkaIdentifier: String = id
}

object DeepCopyActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[DeepCopyActor]),
      idExtractor = {
        case msg: DeepCopyStart =>
          (msg.akkaIdentifier, msg)
        case msg: GetDeepCopyStatus =>
          (msg.akkaIdentifier, msg)
      },
      shardResolver = {
        case msg: DeepCopyStart =>
          (msg.akkaIdentifier.hashCode % 10).toString
        case msg: GetDeepCopyStatus =>
          (msg.akkaIdentifier.hashCode % 10).toString
      })
  }
}
