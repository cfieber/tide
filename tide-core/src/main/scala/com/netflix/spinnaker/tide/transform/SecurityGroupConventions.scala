package com.netflix.spinnaker.tide.transform

import com.netflix.spinnaker.tide.model.AwsApi.{SecurityGroupState, UserIdGroupPairs, IpPermission}

case class SecurityGroupConventions(appName: String) {

  def appSecurityGroupForElbName = s"$appName-elb"

  def constructClassicLinkIpPermission: IpPermission = {
    IpPermission (
      fromPort = Some(80),
      toPort = Some(65535),
      ipProtocol = "tcp",
      ipRanges = Set(),
      userIdGroupPairs = Set(UserIdGroupPairs(
        groupId = None,
        groupName = Some("nf-classiclink"),
        userId = ""
      ))
    )
  }

  def constructAppElbIpPermission: IpPermission = {
    IpPermission (
      fromPort = Some(7001),
      toPort = Some(7002),
      ipProtocol = "tcp",
      ipRanges = Set(),
      userIdGroupPairs = Set (
        UserIdGroupPairs (
          groupId = None,
          groupName = Some(appSecurityGroupForElbName),
          userId = ""
        )
      )
    )
  }

  def constructAppSecurityGroupForElb: SecurityGroupState = {
    var ipPermissions = Set(
      IpPermission(
        fromPort = Some(80),
        toPort = Some(80),
        ipProtocol = "tcp",
        ipRanges = Set("0.0.0.0/0"),
        userIdGroupPairs = Set()
      ),
      IpPermission(
        fromPort = Some(443),
        toPort = Some(443),
        ipProtocol = "tcp",
        ipRanges = Set("0.0.0.0/0"),
        userIdGroupPairs = Set()
      )
    )
    SecurityGroupState(appSecurityGroupForElbName, ipPermissions, "")
  }

}
