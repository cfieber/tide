package com.netflix.spinnaker.tide.actor.aws

import akka.actor.{Props, ActorRef, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.aws.PollingActor.{Poll, Start}
import com.netflix.spinnaker.tide.api.EddaService
import scala.concurrent.duration.DurationInt

trait PollingActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var account: String = _
  var region: String = _
  var eddaUrlTemplate: Option[String] = None
  var cloudDriver: CloudDriverActor.Ref = _
  var eddaService: EddaService = _

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private val scheduledPolling = scheduler.schedule(0 seconds, 15 seconds, self, Poll())
  override def postStop(): Unit = scheduledPolling.cancel()

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  def constructEddaService(): EddaService = {
    new EddaServiceBuilder().constructEddaService(account, region, eddaUrlTemplate.get)
  }

  def resourceCluster(typeName: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(typeName)
  }

  override def receiveCommand: Receive = {
    case event: Start =>
      persist(event) { it =>
        updateState(it)
        eddaService = constructEddaService()
      }

    case event: Poll =>
      poll()
  }

  def poll()

  def updateState(event: Start) = {
    event match {
      case event: Start =>
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

sealed trait PollingProtocol extends Serializable

object PollingActor {
  case class Poll() extends PollingProtocol
  case class Start(account: String, region: String, eddaUrlTemplate: String, cloudDriver: CloudDriverActor.Ref)
    extends PollingProtocol with AkkaClustered {
    override val akkaIdentifier: String = s"$account.$region"
  }
}

trait PollingActorObject {
  val typeName: String = this.getClass.getCanonicalName
  def props: Props

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(props),
      idExtractor = {
        case msg: AkkaClustered =>
          (msg.akkaIdentifier, msg)
      },
      shardResolver = {
        case msg: AkkaClustered =>
          (msg.akkaIdentifier.hashCode % 10).toString
      })
  }

}

