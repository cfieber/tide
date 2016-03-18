package com.netflix.spinnaker.tide.config

import javax.annotation.PostConstruct

import com.amazonaws.retry.RetryPolicy
import com.netflix.awsobjectmapper.AmazonObjectMapper
import com.netflix.spinnaker.cats.provider.ProviderSynchronizerTypeWrapper
import com.netflix.spinnaker.clouddriver.aws.bastion.BastionConfig
import com.netflix.spinnaker.clouddriver.aws.security.{AmazonCredentialsInitializer, AmazonClientProvider, NetflixAmazonCredentials}
import com.netflix.spinnaker.clouddriver.security.{MapBackedAccountCredentialsRepository, AccountCredentialsRepository, ProviderUtils}
import com.netflix.spinnaker.tide.config.AwsConfig.AwsProviderSynchronizer
import com.netflix.spinnaker.tide.model.AwsServiceProviderFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation._
import collection.JavaConverters._
import scala.collection.mutable

@Configuration
@ConditionalOnProperty(Array("aws.enabled"))
@EnableConfigurationProperties
@Import(Array( classOf[BastionConfig], classOf[AmazonCredentialsInitializer] ))
class AwsConfig {

  @Bean
  @DependsOn(Array("netflixAmazonCredentials"))
  def awsServiceProviderFactory(accountCredentialsRepository: AccountCredentialsRepository, amazonClientProvider: AmazonClientProvider): AwsServiceProviderFactory = {
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

  @Bean
  def amazonClientProvider(): AmazonClientProvider = {
    new AmazonClientProvider.Builder().objectMapper(new AmazonObjectMapper()).build()
  }

  @Bean
  def accountCredentialsRepository(): AccountCredentialsRepository = {
    new MapBackedAccountCredentialsRepository()
  }
}

object AwsConfig {
  var awsServiceProviderFactory: AwsServiceProviderFactory = _
  case class AwsProviderSynchronizer()
}
