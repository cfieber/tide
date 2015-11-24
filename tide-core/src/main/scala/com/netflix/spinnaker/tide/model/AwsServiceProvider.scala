package com.netflix.spinnaker.tide.model

import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.netflix.spinnaker.clouddriver.aws.security.{NetflixAmazonCredentials, AmazonClientProvider}
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation

case class AwsServiceProvider(location: AwsLocation, amazonClientProvider: AmazonClientProvider,
                              cred: NetflixAmazonCredentials) {

  def getAmazonEC2: AmazonEC2 = {
    amazonClientProvider.getAmazonEC2(cred, location.region)
  }

  def getAmazonCloudWatch: AmazonCloudWatch = {
    amazonClientProvider.getAmazonCloudWatch(cred, location.region)
  }

  def getAmazonElasticLoadBalancing: AmazonElasticLoadBalancing = {
    amazonClientProvider.getAmazonElasticLoadBalancing(cred, location.region)
  }

  def getAutoScaling: AmazonAutoScaling = {
    amazonClientProvider.getAutoScaling(cred, location.region)
  }

  def getCloudWatch: AmazonCloudWatch = {
    amazonClientProvider.getCloudWatch(cred, location.region)
  }

}

case class AwsServiceProviderFactory(amazonClientProvider: AmazonClientProvider,
                              amazonCredentials: Set[NetflixAmazonCredentials]) {

  def getAwsServiceProvider(location: AwsLocation): Option[AwsServiceProvider] = {
    val credOption = amazonCredentials.find (_.getName == location.account)
    credOption match {
      case Some(cred) => Option(AwsServiceProvider(location, amazonClientProvider, cred))
      case _ => None
    }
  }

}
