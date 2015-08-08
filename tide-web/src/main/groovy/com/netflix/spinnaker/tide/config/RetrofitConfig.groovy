package com.netflix.spinnaker.tide.config

import com.netflix.spinnaker.config.OkHttpClientConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit.client.Client;
import retrofit.client.OkClient;

@Configuration
public class RetrofitConfig {
  @Autowired
  OkHttpClientConfiguration okHttpClientConfiguration

  @Bean
  Client retrofitClient() {
    return new OkClient(okHttpClientConfiguration.create())
  }
}
