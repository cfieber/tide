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

package com.netflix.spinnaker.tide.actor.aws

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.netflix.spinnaker.tide.actor.aws.AwsApi._
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.actor.aws.CloudDriverActor.{GetTaskDetail, CloudDriverResponse}
import com.netflix.spinnaker.tide.actor.aws.DeepCopyActor._
import com.netflix.spinnaker.tide.actor.aws.DeepCopyDirector.{Target, DeepCopyOptions}
import com.netflix.spinnaker.tide.actor.aws.VpcPollingActor.GetVpcs
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.pattern.ask

class DeepCopyActor() extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)
  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var checkForCreatedResources: Cancellable = _
  override def postStop(): Unit = checkForCreatedResources.cancel()

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
  var resourcesRequired: Set[AwsIdentity] = Set()
  var resourcesFound: Set[AwsIdentity] = Set()
  var loadBalancerNameTargetToSource: Map[String, String] = Map()

  var cloudDriverReference: Option[ActorRef] = None
  var cloneServerGroupTaskId: Option[String] = None

  var isComplete = false

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
      sender ! DeepCopyStatus(history, cloneServerGroupTaskId)

    case event: DeepCopyStart =>
      checkForCreatedResources = scheduler.schedule(15 seconds, 15 seconds, self, CheckForCreatedResources())
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

    case event: DeepCopyContinue =>
      checkForCreatedResources = scheduler.schedule(15 seconds, 15 seconds, self, CheckForCreatedResources())
      awsResource = event.awsResource
      awsResource ! AwsResourceProtocol(sourceReference, GetServerGroup(), None)

    case event: DeepCopyComplete =>
      if (!isComplete) {
        persist(event) { it =>
          updateState(it)
          checkForCreatedResources.cancel()
          deepCopyDirector ! it
        }
      }

    case event: CheckForCreatedResources =>
      (cloudDriverReference, cloneServerGroupTaskId) match {
        case (Some(cloudDriver), Some(id)) =>
          cloudDriver ! GetTaskDetail(id)
        case _ =>
          resourcesRequired.diff(resourcesFound).foreach {
            case identity: SecurityGroupIdentity =>
              awsResource ! AwsResourceProtocol(AwsReference(target.location, identity.copy(vpcId = targetVpc.map(_.vpcId))),
                GetSecurityGroup(), None)
            case identity: LoadBalancerIdentity =>
              awsResource ! AwsResourceProtocol(AwsReference(target.location, identity), GetLoadBalancer(), None)
          }
      }

    case event: Requires =>
      if (!resourcesRequired.contains(event.awsIdentity)) {
        persist(event) { e =>
          updateState(e)
        }
        val request = event match {
          case Requires(identity: LoadBalancerIdentity) =>
            AwsResourceProtocol(AwsReference(target.location, identity), GetLoadBalancer(), None)
          case Requires(identity: SecurityGroupIdentity) =>
            AwsResourceProtocol(AwsReference(target.location, identity.copy(vpcId = targetVpc.map(_.vpcId))), GetSecurityGroup(), None)
        }
        awsResource ! request
      }

    case event: Found =>
      if (!resourcesFound.contains(event.awsIdentity)) {
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
          cloudDriverReference = Option(cloudDriverResponse.cloudDriverReference)
          cloneServerGroupTaskId = Option(cloudDriverResponse.taskDetail.id)
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
            persist(AwsResourceProtocol(referenceToUpsert, upsert)) { it =>
              updateState(it)
              awsResource ! it
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
              persist(AwsResourceProtocol(referenceToUpsert, upsert)) { it =>
                updateState(it)
                awsResource ! it
              }
          }
      }

    case event: CloudDriverResponse =>
        updateState(event)
        cloneServerGroupTaskId.foreach { id =>
          if (event.taskDetail.id == id) {
            val taskDetail = event.taskDetail
            val options = DeepCopyOptions(sourceReference, target)
            if (event.taskDetail.status.completed) {
              val resultName = "serverGroupNames"
              var newServerGroupName: String = ""
              val resultMapOption: Option[Map[String, Any]] = taskDetail.resultObjects.find(_.contains(resultName))
              resultMapOption.foreach { resultMap =>
                val resultOption = resultMap.get(resultName)
                resultOption.foreach { result =>
                  val resultList = result.asInstanceOf[List[String]]
                  val resultParts = resultList.head.split(":")
                  newServerGroupName = resultParts(1)
                }
              }
              self ! DeepCopySuccess(options, newServerGroupName)
            }
            if (event.taskDetail.status.failed) {
              self ! DeepCopyFailure(options, event.taskDetail.status.status)
            }
          }
        }
  }

  def startServerGroupCloning() = {
    if (resourcesRequired.diff(resourcesFound).isEmpty) {
      self ! StartServerGroupCloning()
    }
  }

  def updateState(event: Any) = {
    event match {
      case event: DeepCopyStart =>
        awsResource = event.awsResource
        sourceReference = event.options.source
        target = event.options.target
        targetVpc = event.targetVpc
        resourcesRequired = Set()
        resourcesFound = Set()
        loadBalancerNameTargetToSource = Map()
        cloneServerGroupTaskId = None
        isComplete = false
        history = s"Start deep copy of ${sourceReference.akkaIdentifier}" :: history
      case event: DeepCopyFailure =>
        isComplete = true
        history = s"Failure: ${event.message}" :: history
      case event: DeepCopySuccess =>
        isComplete = true
        history = s"Deep copy succeeded. Created '${event.newServerGroupName}'" :: history
      case Requires(identity: AwsIdentity) =>
        history = s"Requires ${identity.akkaIdentifier}" :: history
        resourcesRequired += identity
      case Found(identity: AwsIdentity) =>
        history = s"Found ${identity.akkaIdentifier}" :: history
        resourcesFound += identity
      case AwsResourceProtocol(reference, upsert: UpsertLoadBalancer, _) =>
        history = s"Creating Load Balancer ${reference.akkaIdentifier}" :: history
      case AwsResourceProtocol(reference, upsert: UpsertSecurityGroup, _) =>
        history = s"Creating Security Group ${reference.akkaIdentifier}" :: history
      case event: CloneServerGroupTask =>
        history = s"Cloning Server Group" :: history
        cloneServerGroupTaskId = Option(event.response.taskDetail.id)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
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

sealed trait DeepCopyProtocol

object DeepCopyActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  sealed trait DeepCopyComplete extends DeepCopyProtocol
  case class DeepCopySuccess(options: DeepCopyOptions, newServerGroupName: String) extends DeepCopyComplete
  case class DeepCopyFailure(options: DeepCopyOptions, message: String) extends DeepCopyComplete

  case class CheckForCreatedResources() extends DeepCopyProtocol
  case class Requires(awsIdentity: AwsIdentity) extends DeepCopyProtocol
  case class Found(awsIdentity: AwsIdentity) extends DeepCopyProtocol
  case class StartServerGroupCloning() extends DeepCopyProtocol
  case class CloneServerGroupTask(response: CloudDriverResponse) extends DeepCopyProtocol
  case class DeepCopyStatus(history: List[Any], cloneServerGroupTaskId: Option[String]) extends DeepCopyProtocol

  case class DeepCopyStart(awsResource: ActorRef, options: DeepCopyOptions, targetVpc: Option[Vpc] = None) extends DeepCopyProtocol with AkkaClustered {
    val akkaIdentifier: String = options.akkaIdentifier
  }
  case class DeepCopyContinue(awsResource: ActorRef, options: DeepCopyOptions, targetVpc: Option[Vpc] = None) extends DeepCopyProtocol with AkkaClustered {
    val akkaIdentifier: String = options.akkaIdentifier
  }

  case class GetDeepCopyStatus(id: String) extends DeepCopyProtocol with  AkkaClustered {
    val akkaIdentifier: String = id
  }

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
