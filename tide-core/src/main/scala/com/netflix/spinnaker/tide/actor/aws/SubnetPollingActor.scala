package com.netflix.spinnaker.tide.actor.aws

import akka.actor.{Props, ActorRef}
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.tide.actor.aws.AwsApi.Subnet
import com.netflix.spinnaker.tide.actor.aws.PollingActor.PollingClustered
import com.netflix.spinnaker.tide.actor.aws.SubnetPollingActor.GetSubnets

class SubnetPollingActor extends PollingActor {

  var subnets: List[Subnet] = _

  override def receiveCommand: Receive = {
    case event: GetSubnets =>
      sender() ! subnets
    case event =>
      super.receiveCommand(event)
  }

  override def poll() = {
    subnets = eddaService.subnets
  }
}

object SubnetPollingActor extends PollingActorObject {
  type Ref = ActorRef
  val props = Props[SubnetPollingActor]

  case class GetSubnets(account: String, region: String) extends PollingClustered {
    @JsonIgnore override val pollingIdentifier = s"$account.$region"
  }

}
