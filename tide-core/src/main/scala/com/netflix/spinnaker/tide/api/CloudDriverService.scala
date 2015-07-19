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

package com.netflix.spinnaker.tide.api

import com.netflix.spinnaker.tide.api.CloudDriverService.{TaskReference, TaskDetail}
import retrofit.http._

trait CloudDriverService {

@Headers (Array ("Accept: application/json") )
@GET ("/task/{taskId}") def getTaskDetail (@Path ("taskId") taskId: String): TaskDetail

@Headers (Array ("Accept: application/json") )
@POST ("/ops") def submitTask (@Body cloudDriverOperations: List[Map[String, CloudDriverOperation]]): TaskReference

}

object CloudDriverService {

  case class TaskReference(id: String,
                        error: String,
                        errors: List[String],
                        status: String)

  case class TaskDetail(id: String,
                        startTimeMs: Long,
                        status: Status,
                        history: List[LogMessage],
                        resultObjects: List[Map[String, Any]]) {
    def getCreatedServerGroups: List[String] = {
      if (!status.completed) { return Nil }
      val resultName = "serverGroupNames"
      var newServerGroupNames: List[String] = Nil
      val resultMapOption: Option[Map[String, Any]] = resultObjects.find(_.contains(resultName))
      resultMapOption.foreach { resultMap =>
        val resultOption = resultMap.get(resultName)
        resultOption.foreach { result =>
          val resultList = result.asInstanceOf[List[String]]
          val resultParts = resultList.head.split(":")
          val serverGroupName: String = resultParts(1)
          newServerGroupNames ::= serverGroupName
        }
      }
      newServerGroupNames
    }
  }

  case class Status(phase: String,
                    status: String,
                    completed: Boolean,
                    failed: Boolean)

  case class LogMessage(status: String,
                        phase: String)
}

