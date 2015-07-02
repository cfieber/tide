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

package com.netflix.spinnaker.tide.actor.sync

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import akka.util.Timeout
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.actor.sync.AwsApi._
import scala.concurrent.duration.DurationInt

class DeepCopyDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  implicit val timeout = Timeout(5 seconds)

  var awsResource: AwsResourceActor.Ref = _

  var currentDeepCopiesById: Map[String, DeepCopyOptions] = Map()

  def deepCopyCluster: ActorRef = {
    ClusterSharding.get(context.system).shardRegion(DeepCopyActor.typeName)
  }

  override def receiveCommand: Receive = {
    case event: AwsResourceReference =>
      awsResource = event.awsResource
      context become directDeepCopies
      currentDeepCopiesById.foreach {
        case (id, options) => deepCopyCluster ! DeepCopyContinue(awsResource, options)
      }
    case event => Nil
  }

  def directDeepCopies: Receive = {
    case event: AwsResourceReference =>
      awsResource = event.awsResource

    case event: GetAllDeepCopyTasks =>
      sender() ! currentDeepCopiesById.keySet

    case event: GetDeepCopyStatus =>
      deepCopyCluster forward event

    case event: DeepCopyOptions =>
      persist(event) { it =>
        updateState(it)
        deepCopyCluster ! DeepCopyStart(awsResource, event)
      }

    case event: DeepCopyComplete =>
      persist(event) { it =>
        updateState(it)
      }

    case event: DeepCopyFailure =>
      persist(event) { it =>
        updateState(it)
      }
  }

  override def receiveRecover: Receive = {
    case event: RecoveryCompleted => Nil
    case event =>
      updateState(event)
  }

  def updateState(event: Any) = {
    event match {
      case event: DeepCopyOptions =>
        currentDeepCopiesById += (event.akkaIdentifier -> event)
      case event: DeepCopyComplete =>
        currentDeepCopiesById -= event.options.akkaIdentifier
      case event: DeepCopyFailure =>
        currentDeepCopiesById -= event.options.akkaIdentifier
    }
  }
}

case class GetAllDeepCopyTasks()
case class DeepCopyOptions(source: AwsReference[AutoScalingGroupIdentity], target: Target) extends AkkaClustered {
  val akkaIdentifier: String = s"DeepCopy.${source.akkaIdentifier}.${target.akkaIdentifier}"
}
case class Target(account: String, region: String, vpcName: String) extends AkkaClustered {
  @JsonIgnore def location: AwsLocation = {
    AwsLocation(account, region)
  }
  val akkaIdentifier: String = s"${location.akkaIdentifier}.$vpcName"
}

object DeepCopyDirector {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[DeepCopyDirector]),
      idExtractor = {
        case msg => ("singleton", msg)
      },
      shardResolver = {
        case msg => "singleton"
      })
  }
}
