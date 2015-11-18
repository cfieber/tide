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

package com.netflix.spinnaker.tide.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.tide.model.CloudDriverService
import org.springframework.beans.factory.annotation.{Value, Autowired}
import org.springframework.context.annotation.{Bean, Configuration}
import retrofit.converter.JacksonConverter
import retrofit.{RestAdapter, Endpoint}
import retrofit.Endpoints._
import retrofit.client.Client

@Configuration
class CloudDriverRetrofitConfig {

  @Autowired var retrofitClient: Client = _

  @Bean
  def cloudDriverEndpoint(@Value("${cloudDriver.baseUrl}") cloudDriverApiUrl: String): Endpoint = {
    newFixedEndpoint(cloudDriverApiUrl)
  }

  @Bean
  def cloudDriverService(cloudDriverEndpoint: Endpoint, objectMapper: ObjectMapper, retrofitLogLevel: RestAdapter.LogLevel): CloudDriverService = {
    new RestAdapter.Builder().
      setEndpoint(cloudDriverEndpoint).
      setClient(retrofitClient).
      setConverter(new JacksonConverter(objectMapper)).
      setLogLevel(retrofitLogLevel).
      build.create(classOf[CloudDriverService])
  }

}
