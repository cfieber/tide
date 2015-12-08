package com.netflix.spinnaker.tide.actor.polling

import java.util.Date

import akka.actor.{ActorRef, Props, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.SingletonActorObject
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.ClassicLinkSecurityGroupNames
import com.netflix.spinnaker.tide.actor.polling.AwsPollingActor.AwsPoll
import com.netflix.spinnaker.tide.actor.polling.PipelinePollingActor.PipelinePoll
import com.netflix.spinnaker.tide.actor.polling.PollingDirector.{Poll, PollInit}
import com.netflix.spinnaker.tide.model.AwsApi.AwsLocation
import scala.concurrent.duration.DurationInt

class PollingDirector extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  var pollInit: Option[PollInit] = None

  private implicit val dispatcher = context.dispatcher
  val tick = context.system.scheduler.schedule(0 seconds, 15 seconds, self, Poll())
  log.info(s"******* start poll ${new Date().getTime} - $pollInit")

  override def postStop() = {
    log.info(s"******* cancel poll ${new Date().getTime}")
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
    case poll: Poll =>
      log.info(s"******* poll, but not yet polling ${new Date().getTime} - $pollInit")
    case _ =>
  }

  def polling(pollInit: PollInit): Receive = {
    case poll: Poll =>
      log.info(s"******* poll ${new Date().getTime} - $pollInit")
      getShardCluster(PipelinePollingActor.typeName) ! PipelinePoll()
      val pollers: Seq[PollingActorObject] =Seq(VpcPollingActor, ClassicLinkInstanceIdPollingActor,
        SecurityGroupPollingActor, LoadBalancerPollingActor, ServerGroupPollingActor)
      val accounts: Set[String] = pollInit.accountsToRegions.keySet
      for (account <- accounts) {
        val regions = pollInit.accountsToRegions.getOrElse(account, Nil)
        for (region <- regions) {
          val location = AwsLocation(account, region)
          for (poller <- pollers) {
            getShardCluster(poller.typeName) ! AwsPoll(location)
          }
          val classicLinkSecurityGroupNames: Seq[String] = pollInit.classicLinkSecurityGroupNames
          getShardCluster(ClassicLinkInstancesActor.typeName) ! ClassicLinkSecurityGroupNames(location,
            classicLinkSecurityGroupNames)
        }
      }
    case pollInit: PollInit =>
      log.info(s"******* init but already polling ${new Date().getTime} - $pollInit")
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

  case class PollInit(accountsToRegions: Map[String, Set[String]], classicLinkSecurityGroupNames: Seq[String]) extends PollingDirectorProtocol
  case class Poll() extends PollingDirectorProtocol

}
