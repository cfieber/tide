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

package com.netflix.spinnaker.tide.actor.service

import akka.actor.{ActorLogging, Actor}
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.fasterxml.jackson.databind.DeserializationFeature._
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.databind.SerializationFeature._
import com.fasterxml.jackson.datatype.jsr310.JSR310Module
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import retrofit.Endpoints._
import retrofit.RestAdapter.{Builder, LogLevel}
import retrofit.client.OkClient
import retrofit.converter.JacksonConverter

trait RetrofitServiceActor[T] extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var init: Option[RetrofitServiceInit[T]] = None
  var service: T = _

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def receiveCommand: Receive = {
    case msg: RetrofitServiceInit[T] =>
      updateState(msg)
      service = init.get.constructService
      context become operational
    case _ => Nil
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      init match {
        case Some(config) =>
          service = config.constructService
          context become operational
        case None => Nil
      }
    case msg: RetrofitServiceInit[_] =>
      updateState(msg)
  }

  private def updateState(event: Any) = {
    event match {
      case msg: RetrofitServiceInit[T] => init = Some(msg)
      case _ => Nil
    }
  }

  def operational: Receive

}

trait RetrofitServiceInit[T] {
  val url: String
  val logLevel: LogLevel = LogLevel.BASIC
  val serviceType: Class[T]

  def constructService: T = {
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
      setLogLevel(logLevel).
      build.create(serviceType)
  }
}

