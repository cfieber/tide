package com.netflix.spinnaker.tide.config

import com.netflix.spinnaker.config.OkHttpClientConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.{Bean, Configuration}
import retrofit.client.{OkClient, Client}

@Configuration
class RetrofitConfig {

  @Autowired
  var okHttpClientConfiguration: OkHttpClientConfiguration = _

  @Bean
  def retrofitClient(): Client = {
    new OkClient(okHttpClientConfiguration.create())
  }
}
