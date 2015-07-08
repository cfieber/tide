package com.netflix.spinnaker.tide.actor.aws

import akka.persistence.RecoveryCompleted
import com.netflix.spinnaker.tide.actor.aws.EddaPollingActor.EddaStart
import com.netflix.spinnaker.tide.actor.aws.PollingActor.{PollingClustered, Start}
import com.netflix.spinnaker.tide.api.EddaService

trait EddaPollingActor extends PollingActor {

  var account: String = _
  var region: String = _
  var eddaUrlTemplate: Option[String] = None
  var cloudDriver: CloudDriverActor.Ref = _
  var eddaService: EddaService = _

  def constructEddaService(): EddaService = {
    new ServiceBuilder().constructEddaService(account, region, eddaUrlTemplate.get)
  }

  def start() = {
    eddaService = constructEddaService()
  }

  def poll()

  def updateState(event: Start) = {
    event match {
      case event: EddaStart =>
        account = event.account
        region = event.region
        eddaUrlTemplate = Option(event.eddaUrlTemplate)
        cloudDriver = event.cloudDriver
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      if (eddaUrlTemplate.isDefined) {
        eddaService = constructEddaService()
      }
    case event: Start =>
      updateState(event)
  }
}

object EddaPollingActor {
  case class EddaStart(account: String, region: String, eddaUrlTemplate: String, cloudDriver: CloudDriverActor.Ref)
    extends Start with PollingClustered {
    override val pollingIdentifier: String = s"$account.$region"
  }
}
