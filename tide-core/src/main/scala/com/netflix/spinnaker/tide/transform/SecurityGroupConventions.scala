package com.netflix.spinnaker.tide.transform

import com.netflix.spinnaker.tide.model.AwsApi.{AccountIdentifier, UserIdGroupPairs, IpPermission}

case class SecurityGroupConventions(appName: String, accountName: String, vpcName: Option[String]) {

  private val accountsToClassicLink: Map[String, String] = Map(
    "persistence_test" -> "nf-classiclink-rds"
  )

  private def constructClassicLinkIpPermission: IpPermission = {
    IpPermission (
      fromPort = Some(80),
      toPort = Some(65535),
      ipProtocol = "tcp",
      ipRanges = Set(),
      userIdGroupPairs = Set(UserIdGroupPairs(
        groupId = None,
        groupName = Some(accountsToClassicLink.getOrElse(accountName, "nf-classiclink")),
        AccountIdentifier("", Some(accountName)),
        vpcName
      ))
    )
  }

  private def constructAppIngress(includeElbSecurityGroup: Boolean): Set[IpPermission] = {
    var userIdGroupPairs: Set[UserIdGroupPairs] = Set()
    if (includeElbSecurityGroup) {
      userIdGroupPairs = Set (
        UserIdGroupPairs (
          groupId = None,
          groupName = Some(SecurityGroupConventions.appSecurityGroupForElbName(appName)),
          AccountIdentifier("", Some(accountName)),
          vpcName
        )
      )
    }
    Set(IpPermission (
      fromPort = Some(7001),
      toPort = Some(7002),
      ipProtocol = "tcp",
      ipRanges = Set(),
      userIdGroupPairs = userIdGroupPairs
    ))
  }

  def appendBoilerplateIngress(groupName: String, allowIngressFromClassic: Boolean, includeElbSecurityGroup: Boolean): Set[IpPermission] = {
    groupName match {
      case name if name == appName =>
        addClassicLinkPermission(constructAppIngress(includeElbSecurityGroup), allowIngressFromClassic)
      case name if name == SecurityGroupConventions.appSecurityGroupForElbName(appName) =>
        addClassicLinkPermission(Set(), allowIngressFromClassic)
      case _ =>
        Set()
    }
  }

  private def addClassicLinkPermission(ingress: Set[IpPermission], allowIngressFromClassic: Boolean): Set[IpPermission] = {
    if (allowIngressFromClassic) {
    ingress + constructClassicLinkIpPermission
    } else {
    ingress
    }
  }

}

object SecurityGroupConventions {
  def appSecurityGroupForElbName(appName: String) = s"$appName-elb"
}
