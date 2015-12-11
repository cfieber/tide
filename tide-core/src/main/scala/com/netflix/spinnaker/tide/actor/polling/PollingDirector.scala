package com.netflix.spinnaker.tide.actor.polling

import java.util.Date

import akka.actor.{ActorRef, Props, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.clouddriver.aws.security.config.CredentialsConfig
import com.netflix.spinnaker.clouddriver.aws.security.config.CredentialsConfig.Account
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.ClassicLinkSecurityGroupNames
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.AwsPoll
import com.netflix.spinnaker.tide.actor.polling.PipelinePollingActor.PipelinePoll
import com.netflix.spinnaker.tide.actor.polling.PollingDirector.{Poll, PollInit}
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation
import scala.concurrent.duration.DurationInt
import scala.collection.JavaConverters._

class PollingDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var pollInit: Option[PollInit] = None

  private implicit val dispatcher = context.dispatcher
  val tick = context.system.scheduler.schedule(0 seconds, 15 seconds, self, Poll())

  override def postStop() = {
    tick.cancel()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  def getShardCluster(name: String): ActorRef = {
    ClusterSharding.get(context.system).shardRegion(name)
  }

  override def receiveCommand: Receive = {
    case pollInit: PollInit =>
      persist(pollInit) { init =>
        updateState(init)
        context.become(polling(init))
      }
    case _ =>
  }

  def polling(pollInit: PollInit): Receive = {
    case poll: Poll =>
      getShardCluster(PipelinePollingActor.typeName) ! PipelinePoll()
      val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, ClassicLinkInstanceIdPollingActor,
        SecurityGroupPollingActor, LoadBalancerPollingActor, ServerGroupPollingActor)
      val accounts: Set[Account] = pollInit.credentialsConfig.getAccounts.asScala.toSet
      for (account <- accounts) {
        val regions = account.getRegions.asScala
        for (region <- regions) {
          val location = AwsLocation(account.getName, region.getName)
          for (poller <- pollers) {
            getShardCluster(poller.typeName) ! AwsPoll(location)
          }
          val classicLinkSecurityGroupNames: Seq[String] = pollInit.classicLinkSecurityGroupNames
          getShardCluster(ClassicLinkInstancesActor.typeName) ! ClassicLinkSecurityGroupNames(location,
            classicLinkSecurityGroupNames)
        }
      }
    case _ =>
  }

  override def receiveRecover: Receive = {
    case event: RecoveryCompleted =>
      pollInit.foreach { init =>
        context.become(polling(init))
      }
    case event =>
      updateState(event)
  }

  def updateState(event: Any) = {
    event match {
      case init: PollInit =>
        pollInit = Option(init)
      case _ =>
    }
  }
}

sealed trait PollingDirectorProtocol extends Serializable

object PollingDirector extends SingletonActorObject {
  val props = Props[PollingDirector]

  case class PollInit(credentialsConfig: CredentialsConfig, classicLinkSecurityGroupNames: Seq[String]) extends PollingDirectorProtocol
  case class Poll() extends PollingDirectorProtocol

}
