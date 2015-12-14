package com.netflix.spinnaker.tide.akka

sealed trait PingPong
case class Ping() extends PingPong
case class Pong() extends PingPong

