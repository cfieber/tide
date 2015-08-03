package com.netflix.spinnaker.tide.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.tide.api.Front50Service
import org.springframework.beans.factory.annotation.{Value, Autowired}
import org.springframework.context.annotation.{Bean, Configuration}
import retrofit.Endpoints._
import retrofit.converter.JacksonConverter
import retrofit.{RestAdapter, Endpoint}
import retrofit.client.Client

@Configuration
class Front50Config {

  @Autowired var retrofitClient: Client = _

  @Bean
  def front50Endpoint(@Value("${front50Api.baseUrl}") front50ApiUrl: String): Endpoint = {
    newFixedEndpoint(front50ApiUrl)
  }

  @Bean
  def front50Service(front50Endpoint: Endpoint, objectMapper: ObjectMapper, retrofitLogLevel: RestAdapter.LogLevel): Front50Service = {
    new RestAdapter.Builder().
      setEndpoint(front50Endpoint).
      setClient(retrofitClient).
      setConverter(new JacksonConverter(objectMapper)).
      setLogLevel(RestAdapter.LogLevel.BASIC).
      build.create(classOf[Front50Service])
  }

}
