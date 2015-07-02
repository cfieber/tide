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

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.PersistentActor
import com.netflix.spinnaker.tide.actor.sync.AwsApi._
import com.netflix.spinnaker.tide.api.UpsertSecurityGroupOperation
import scala.concurrent.duration.DurationInt

class SecurityGroupActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  context.setReceiveTimeout(60 seconds)
  def scheduler = context.system.scheduler
  private implicit val dispatcher = context.dispatcher
  var latestStateTimeout = scheduler.scheduleOnce(30 seconds, self, LatestStateTimeout)

  var awsReference: AwsReference[SecurityGroupIdentity] = _
  var cloudDriver: Option[ActorRef] = None
  var desiredState: Option[UpsertSecurityGroup] = None
  var latestState: Option[SecurityGroupLatestState] = None

  override def postStop(): Unit = latestStateTimeout.cancel()

  override def receiveCommand: Receive = {
    case wrapper: AwsResourceProtocol[_] =>
      handleAwsResourceProtocol(wrapper.awsReference.asInstanceOf[AwsReference[SecurityGroupIdentity]],
        wrapper.event.asInstanceOf[SecurityGroupEvent], wrapper.cloudDriver)

    case LatestStateTimeout =>
      latestState = None
      desiredState.foreach(mutate)

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  private def handleAwsResourceProtocol(newAwsReference: AwsReference[SecurityGroupIdentity], event: SecurityGroupEvent,
                                        newCloudDriverReference: Option[ActorRef]) = event match {

    case event: GetSecurityGroup =>
      updateReferences(newAwsReference, newCloudDriverReference)
      sender() ! new SecurityGroupDetails(newAwsReference, latestState, desiredState)

    case event: UpsertSecurityGroup =>
      updateReferences(newAwsReference, newCloudDriverReference)
      if (desiredState != Option(event)) {
        persist(event) { e =>
          updateState(event)
          desiredState.foreach(mutate)
        }
      }

    case event: SecurityGroupLatestState =>
      updateReferences(newAwsReference, newCloudDriverReference)
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

  private def mutate(upsertSecurityGroup: UpsertSecurityGroup) = {
    latestState match {
      case None =>
        cloudDriver.foreach { cloudDriverActor =>
          val stateWithoutIngress = upsertSecurityGroup.state.copy(ipPermissions = Set())
          val eventWithoutIngress = upsertSecurityGroup.copy(state = stateWithoutIngress)
          cloudDriverActor ! AwsResourceProtocol(awsReference, eventWithoutIngress)
          cloudDriverActor ! AwsResourceProtocol(awsReference, upsertSecurityGroup)
        }
      case Some(latest) =>
        if(UpsertSecurityGroupOperation.from(awsReference, latest.state) == UpsertSecurityGroupOperation.from(awsReference, upsertSecurityGroup.state)) {
          desiredState = None
        } else {
          if (upsertSecurityGroup.overwrite) {
            cloudDriver.foreach(_ ! AwsResourceProtocol(awsReference, upsertSecurityGroup))
          } else {
            desiredState = None
          }
        }
    }
  }

  private def updateReferences(awsReference: AwsReference[SecurityGroupIdentity], newCloudDriverReference: Option[ActorRef]) = {
    if (newCloudDriverReference.isDefined) {
      cloudDriver = newCloudDriverReference
    }
    this.awsReference = awsReference
  }

  private def updateState(event: AwsResourceEvent) = {
    event match {
      case event: UpsertSecurityGroup =>
        desiredState = Option(event)
      case event: SecurityGroupLatestState =>
        latestState = Option(event)
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case event: SecurityGroupEvent =>
      updateState(event)
  }

}

object SecurityGroupActor {
  type Ref = ActorRef
  val typeName: String = this.getClass.getCanonicalName

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(Props[SecurityGroupActor]),
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
