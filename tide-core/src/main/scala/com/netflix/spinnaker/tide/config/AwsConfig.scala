package com.netflix.spinnaker.tide.config

import javax.annotation.PostConstruct

import com.netflix.spinnaker.cats.provider.ProviderSynchronizerTypeWrapper
import com.netflix.spinnaker.clouddriver.aws.security.{AmazonClientProvider, NetflixAmazonCredentials}
import com.netflix.spinnaker.clouddriver.security.{AccountCredentialsRepository, ProviderUtils}
import com.netflix.spinnaker.tide.config.AwsConfig.AwsProviderSynchronizer
import com.netflix.spinnaker.tide.model.AwsServiceProviderFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.{DependsOn, Bean, ComponentScan, Configuration}
import collection.JavaConverters._
import scala.collection.mutable

@Configuration
@ConditionalOnProperty(Array("aws.enabled"))
@ComponentScan(Array("com.netflix.spinnaker.clouddriver.aws", "com.netflix.spinnaker.clouddriver.core"))
class AwsConfig {

  @Autowired var amazonClientProvider: AmazonClientProvider = _

  @Bean
  @DependsOn(Array("netflixAmazonCredentials"))
  def awsServiceProviderFactory(accountCredentialsRepository: AccountCredentialsRepository): AwsServiceProviderFactory = {
    def allAccounts = ProviderUtils.buildThreadSafeSetOfAccounts(accountCredentialsRepository,
      classOf[NetflixAmazonCredentials]).asScala
    def allNetflixAccounts = allAccounts.asInstanceOf[mutable.Set[NetflixAmazonCredentials]].toSet
    AwsConfig.awsServiceProviderFactory = AwsServiceProviderFactory(amazonClientProvider, allNetflixAccounts)
    AwsConfig.awsServiceProviderFactory
  }

  @Bean
  def awsProviderSynchronizerTypeWrapper(): AwsProviderSynchronizerTypeWrapper = {
    new AwsProviderSynchronizerTypeWrapper()
  }

  class AwsProviderSynchronizerTypeWrapper extends ProviderSynchronizerTypeWrapper {
    override def getSynchronizerType: Class[AwsProviderSynchronizer] = classOf[AwsProviderSynchronizer]
  }

}

object AwsConfig {
  var awsServiceProviderFactory: AwsServiceProviderFactory = _
  case class AwsProviderSynchronizer()
}
