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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.AwsApi.{AwsReference, LoadBalancerIdentity}
import com.netflix.spinnaker.tide.actor.aws.AwsResourceActor._
import com.netflix.spinnaker.tide.api.UpsertLoadBalancerOperation
import scala.concurrent.duration.DurationInt

class LoadBalancerActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  context.setReceiveTimeout(60 seconds)
  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)

  var awsReference: AwsReference[LoadBalancerIdentity] = _
  var cloudDriver: Option[ActorRef] = None
  var desiredState: Option[UpsertLoadBalancer] = None
  var latestState: Option[LoadBalancerLatestState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {
    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[LoadBalancerIdentity]],
        wrapper.event.asInstanceOf[LoadBalancerEvent], wrapper.cloudDriver)

    case LatestStateTimeout =>
      latestStateTimeout = scheduler.scheduleOnce(60 seconds, self, LatestStateTimeout)
      latestState = None
      desiredState.foreach(mutate)

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[LoadBalancerIdentity], event: LoadBalancerEvent,
                                                     newCloudDriverReference: Option[ActorRef]) = event match {
    case event: GetLoadBalancer =>
      updateReferences(newCloudDriverReference, newAwsReference)
      sender() ! new LoadBalancerDetails(newAwsReference, latestState, desiredState)

    case event: UpsertLoadBalancer =>
      updateReferences(newCloudDriverReference, newAwsReference)
      if (desiredState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          desiredState.foreach(mutate)
        }
      }

    case event: LoadBalancerLatestState =>
      updateReferences(newCloudDriverReference, newAwsReference)
      latestStateTimeout.cancel()
      latestStateTimeout = scheduler.scheduleOnce(60 seconds, self, LatestStateTimeout)
      if (latestState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          desiredState.foreach(mutate)
        }
      } else {
        desiredState.foreach(mutate)
      }
  }

  private def mutate(upsertLoadBalancer: UpsertLoadBalancer) = {
    latestState match {
      case None =>
        cloudDriver.foreach { cloudDriverActor =>
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertLoadBalancer)
        }
      case Some(latest) =>
        if (UpsertLoadBalancerOperation.from(awsReference, latest.state) == UpsertLoadBalancerOperation.from(awsReference, upsertLoadBalancer.state)) {
          desiredState = None
        } else {
          if (upsertLoadBalancer.overwrite) {
            cloudDriver.foreach(_ ! AwsResourceProtocol(awsReference, upsertLoadBalancer))
          } else {
            desiredState = None
          }
        }
    }
  }

  private def updateReferences(newCloudDriverReference: Option[ActorRef], newAwsReference: AwsReference[LoadBalancerIdentity]) = {
    if (newCloudDriverReference.isDefined) {
      cloudDriver = newCloudDriverReference
    }
    awsReference = newAwsReference
  }

  private def updateState(event: AwsResourceEvent) = {
    event match {
      case event: UpsertLoadBalancer =>
        desiredState = Option(event)
      case event: LoadBalancerLatestState =>
        latestState = Option(event)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      self ! LatestStateTimeout
    case event: LoadBalancerEvent =>
      updateState(event)
  }

}

object LoadBalancerActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[LoadBalancerActor]),
      idExtractor = {
        case msg: AwsResourceProtocol[_] =>
          (msg.akkaIdentifier, msg)
      },
      shardResolver = {
        case msg: AwsResourceProtocol[_] =>
          (msg.akkaIdentifier.hashCode % 10).toString
      })
  }
}

