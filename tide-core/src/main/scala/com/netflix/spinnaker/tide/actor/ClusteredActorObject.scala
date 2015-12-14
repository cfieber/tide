package com.netflix.spinnaker.tide.actor

import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion._
import com.netflix.spinnaker.tide.actor.task.TaskProtocol
import com.netflix.spinnaker.tide.model.AkkaClustered

trait ClusteredActorObject {
  val typeName: String = this.getClass.getCanonicalName
  val props: Props

  def idExtractor: IdExtractor = {
    case msg: AkkaClustered =>
      (s"$typeName.${msg.akkaIdentifier}", msg)
  }
  def shardResolver: ShardResolver = {
    case msg: AkkaClustered =>
      (s"$typeName.${msg.akkaIdentifier}".hashCode % 10).toString
  }

  def startCluster(clusterSharding: ClusterSharding) = {
    clusterSharding.start(
      typeName = typeName,
      entryProps = Some(props),
      idExtractor = idExtractor,
      shardResolver = shardResolver
    )
  }
}

trait SingletonActorObject extends ClusteredActorObject {
  override def idExtractor = {
    case msg => (s"$typeName.singleton", msg)
  }
  override def shardResolver = {
    case msg => s"$typeName.singleton"
  }
}

trait TaskActorObject extends ClusteredActorObject{
  override def idExtractor = {
    case msg: TaskProtocol => (msg.taskId, msg)
  }
  override def shardResolver = {
    case msg: TaskProtocol => (msg.taskId.hashCode % 10).toString
  }
}

