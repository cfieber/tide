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

package com.netflix.spinnaker.tide.actor.aws

import com.fasterxml.jackson.databind.DeserializationFeature._
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.databind.SerializationFeature._
import com.fasterxml.jackson.datatype.jsr310.JSR310Module
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.netflix.spinnaker.tide.api.{Front50Service, EddaService}
import retrofit.Endpoints._
import retrofit.RestAdapter.{LogLevel, Builder}
import retrofit.client.OkClient
import retrofit.converter.JacksonConverter

class ServiceBuilder {

  def constructEddaService(account: String, region: String, eddaUrlTemplate: String): EddaService = {
    val eddaUrl = eddaUrlTemplate.replaceAll("%account", account).replaceAll("%region", region)
    constructService(eddaUrl, classOf[EddaService])
  }

  private def constructService[T](url: String, serviceType: Class[T]): T = {
    val objectMapper: ObjectMapper = new ObjectMapper()
    objectMapper.registerModule(DefaultScalaModule)
      .registerModule(new JSR310Module)
      .disable(WRITE_DATES_AS_TIMESTAMPS)
      .enable(ACCEPT_SINGLE_VALUE_AS_ARRAY)
      .enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val endpoint = newFixedEndpoint(url)
    new Builder().
      setEndpoint(endpoint).
      setClient(new OkClient).
      setConverter(new JacksonConverter(objectMapper)).
      setLogLevel(LogLevel.BASIC).
      build.create(serviceType)
  }
}
