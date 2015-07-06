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

import akka.persistence.PersistentView

class DeepCopyView(id: String) extends PersistentView {
  override def persistenceId: String = id
  override def viewId: String = s"$id-view"

  var history: List[Any] = Nil

  def receive: Receive = {
    case event: GetDeepCopyStatus => sender() ! history
    case event => history = event :: history
  }
}

object DeepCopyView {
  case class GetHistory()
}