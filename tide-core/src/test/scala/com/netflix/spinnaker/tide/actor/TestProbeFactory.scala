package com.netflix.spinnaker.tide.actor

import akka.actor.{Actor, ActorRef, ActorRefFactory}
import akka.testkit.TestProbe
import com.netflix.akka.spring.ActorFactory

class TestProbeFactory(private val probe: TestProbe) extends ActorFactory {
  override def create(context: ActorRefFactory, `type`: Class[_ <: Actor], initialState: AnyRef*): ActorRef = probe.ref
}
