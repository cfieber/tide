package com.netflix.spinnaker.tide.model

import com.netflix.spinnaker.tide.model.AwsApi._
import scala.collection.JavaConversions._
import com.amazonaws.services.ec2.{model ⇒ awsEc2}
import com.amazonaws.services.autoscaling.{model ⇒ awsAutoScaling}
import com.amazonaws.services.elasticloadbalancing.{model ⇒ awsLoadbalancing}

object AwsConversion {
  val nameKey = "Name"

  def vpcFrom(awsVpc: awsEc2.Vpc, classicLinkEnabled: Boolean): Vpc = {
    val nameTagOption: Option[awsEc2.Tag] = awsVpc.getTags.find(_.getKey == nameKey)
    Vpc(
      vpcId = awsVpc.getVpcId,
      name = nameTagOption.map(_.getValue),
      classicLinkEnabled = classicLinkEnabled
    )
  }

  def subnetFrom(awsSubnet: awsEc2.Subnet): Subnet = {

    def getSubnetType: String = {
      awsSubnet.getTags.find(tag => nameKey.equalsIgnoreCase(tag.getKey)) match {
        case Some(subnetNameTag) =>
          val subnetName = subnetNameTag.getValue
          val parts = subnetName.split("\\.")
          try {
            s"${parts(1)} (${parts(0)})"
          } catch {
            case e: Exception => subnetName
          }
        case None => ""
      }
    }

    Subnet(
      subnetId = awsSubnet.getSubnetId,
      vpcId = awsSubnet.getVpcId,
      name = getSubnetType)
  }

  def autoScalingGroupFrom(awsAsg: awsAutoScaling.AutoScalingGroup): AutoScalingGroup = {

    val instances = if (Option(awsAsg.getInstances).isDefined) {
      awsAsg.getInstances.map( instance =>
        Instance(
          instanceId = instance.getInstanceId,
          lifecycleState = instance.getLifecycleState,
          healthStatus = instance.getHealthStatus,
          launchConfigurationName = instance.getLaunchConfigurationName
        )
      )
    } else Nil

    AutoScalingGroup(
      identity = AutoScalingGroupIdentity(awsAsg.getAutoScalingGroupName),
      state = AutoScalingGroupState(
        VPCZoneIdentifier = awsAsg.getVPCZoneIdentifier,
        launchConfigurationName = awsAsg.getLaunchConfigurationName,
        availabilityZones = awsAsg.getAvailabilityZones.toSet,
        defaultCooldown = awsAsg.getDefaultCooldown,
        desiredCapacity = awsAsg.getDesiredCapacity,
        healthCheckGracePeriod = awsAsg.getHealthCheckGracePeriod,
        healthCheckType = awsAsg.getHealthCheckType,
        loadBalancerNames = awsAsg.getLoadBalancerNames.toSet,
        maxSize = awsAsg.getMaxSize,
        minSize = awsAsg.getMinSize,
        suspendedProcesses = awsAsg.getSuspendedProcesses.map(_.getProcessName).toSet,
        terminationPolicies = awsAsg.getTerminationPolicies.toSet,
        subnetType = None,
        vpcName = None
      ),
      instances = instances
    )
  }

  def launchConfigurationFrom(awsLaunchConfig: awsAutoScaling.LaunchConfiguration): LaunchConfiguration = {

    val isInstanceMonitoringEnabled: Boolean = Option(awsLaunchConfig.getInstanceMonitoring) match {
      case None => false
      case Some(instanceMonitoring) => instanceMonitoring.getEnabled
    }
    val associatePublicIpAddress: Option[Boolean] = Option(awsLaunchConfig.getAssociatePublicIpAddress).map {_.asInstanceOf[Boolean]}
    LaunchConfiguration(
      identity = LaunchConfigurationIdentity(awsLaunchConfig.getLaunchConfigurationName),
      state = LaunchConfigurationState(
        associatePublicIpAddress = associatePublicIpAddress,
        ebsOptimized = awsLaunchConfig.getEbsOptimized,
        iamInstanceProfile = awsLaunchConfig.getIamInstanceProfile,
        imageId = awsLaunchConfig.getImageId,
        isInstanceMonitoringEnabled = isInstanceMonitoringEnabled,
        instanceType = awsLaunchConfig.getInstanceType,
        kernelId = awsLaunchConfig.getKernelId,
        keyName = awsLaunchConfig.getKeyName,
        ramdiskId = awsLaunchConfig.getRamdiskId,
        securityGroups = awsLaunchConfig.getSecurityGroups.toSet,
        spotPrice = Option(awsLaunchConfig.getSpotPrice),
        classicLinkVPCId = Option(awsLaunchConfig.getClassicLinkVPCId)
      )
    )
  }

  def loadBalancerFrom(awsloadBalancer: awsLoadbalancing.LoadBalancerDescription): LoadBalancer = {
    val awsHealthCheck = awsloadBalancer.getHealthCheck
    val healthCheck = if (Option(awsHealthCheck).nonEmpty) {
      HealthCheck(
        healthyThreshold = awsHealthCheck.getHealthyThreshold,
        interval = awsHealthCheck.getInterval,
        target = awsHealthCheck.getTarget,
        timeout = awsHealthCheck.getTimeout,
        unhealthyThreshold = awsHealthCheck.getUnhealthyThreshold
      )
    } else {
      null
    }
    val awsSourceSecurityGroup = awsloadBalancer.getSourceSecurityGroup
    val sourceSecurityGroup = ElbSourceSecurityGroup(
      groupName = awsSourceSecurityGroup.getGroupName,
      ownerAlias = awsSourceSecurityGroup.getOwnerAlias
    )
    LoadBalancer(
      identity = LoadBalancerIdentity(awsloadBalancer.getLoadBalancerName),
      state = LoadBalancerState(
        vpcId = Option(awsloadBalancer.getVPCId),
        availabilityZones = awsloadBalancer.getAvailabilityZones.toSet,
        healthCheck = healthCheck,
        listenerDescriptions = awsloadBalancer.getListenerDescriptions.map { awsListenerDescription =>
          val awsListener = awsListenerDescription.getListener
          ListenerDescription(
            listener = Listener(
              SSLCertificateId = Option(awsListener.getSSLCertificateId),
              instancePort = awsListener.getInstancePort,
              instanceProtocol = awsListener.getInstanceProtocol,
              loadBalancerPort = awsListener.getLoadBalancerPort,
              protocol = awsListener.getProtocol
            ),
            policyNames = awsListenerDescription.getPolicyNames.toSet
          )
        }.toSet,
        scheme = awsloadBalancer.getScheme,
        securityGroups = awsloadBalancer.getSecurityGroups.toSet,
        sourceSecurityGroup = sourceSecurityGroup,
        subnets = awsloadBalancer.getSubnets.toSet,
        subnetType = None
      )
    )
  }

  def securityGroupFrom(awsSecurityGroup: awsEc2.SecurityGroup): SecurityGroup = {
    val awsIpPermissions: Set[awsEc2.IpPermission] = Option(awsSecurityGroup.getIpPermissions) match {
      case Some(permissions) => permissions.toSet
      case _ => Set()
    }
    awsSecurityGroup.getIpPermissions
    SecurityGroup(
      groupId = awsSecurityGroup.getGroupId,
      identity = SecurityGroupIdentity(awsSecurityGroup.getGroupName, Option(awsSecurityGroup.getVpcId)),
      state = SecurityGroupState(
        ownerId = awsSecurityGroup.getOwnerId,
        description = awsSecurityGroup.getDescription,
        ipPermissions = awsIpPermissions.map { awsIpPermission =>
          val userIdGroupPairs = awsIpPermission.getUserIdGroupPairs.map { awsUserIdGroupPair =>
            UserIdGroupPairs(
              groupId = Option(awsUserIdGroupPair.getGroupId),
              groupName = Option(awsUserIdGroupPair.getGroupName),
              userId = awsUserIdGroupPair.getUserId
            )
          }.toSet
          val fromPort: Option[Int] = Option(awsIpPermission.getFromPort).map {_.toInt}
          val toPort: Option[Int] = Option(awsIpPermission.getToPort).map {_.toInt}
          IpPermission(
            fromPort = fromPort,
            toPort = toPort,
            ipProtocol = awsIpPermission.getIpProtocol,
            ipRanges = awsIpPermission.getIpRanges.toSet,
            userIdGroupPairs = userIdGroupPairs
          )
        }
      )
    )
  }

}
