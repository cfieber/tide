package com.netflix.spinnaker.tide.actor.polling

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import com.netflix.spinnaker.tide.actor.ContractActorImpl
import com.netflix.spinnaker.tide.actor.polling.EddaPollingActor.EddaPollingProtocol
import com.netflix.spinnaker.tide.actor.polling.SubnetPollingActor.{LatestSubnets, GetSubnets}
import com.netflix.spinnaker.tide.actor.service.EddaActor.RetrieveSubnets
import com.netflix.spinnaker.tide.model.AwsApi.{AwsLocation, Subnet}

class SubnetPollingActor extends EddaPollingActor {

  override def pollScheduler = new PollSchedulerActorImpl(context, SubnetPollingActor)

  var subnets: Option[List[Subnet]] = None

  override def receive: Receive = {
    case msg: GetSubnets =>
      if (subnets.isEmpty) {
        handlePoll(msg.location)
      }
      sender() ! LatestSubnets(msg.location, subnets.get)
    case msg => super.receive(msg)
  }

  override def handlePoll(location: AwsLocation): Unit = {
    subnets = Some(edda.ask(RetrieveSubnets(location)).resources)
  }

}

object SubnetPollingActor extends PollingActorObject {
  val props = Props[SubnetPollingActor]

  case class GetSubnets(location: AwsLocation) extends EddaPollingProtocol
  case class LatestSubnets(location: AwsLocation, resources: List[Subnet]) extends EddaPollingProtocol
}

trait SubnetPollingContract {
  def ask(msg: GetSubnets): LatestSubnets
}

class SubnetPollingContractActor(val clusterSharding: ClusterSharding) extends SubnetPollingContract
with ContractActorImpl[EddaPollingProtocol] {
  val actorObject = SubnetPollingActor

  def ask(msg: GetSubnets): LatestSubnets = {
    askActor(msg, classOf[LatestSubnets])
  }
}