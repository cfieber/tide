package com.netflix.spinnaker.tide.akka

import akka.actor.{Actor, ActorRef, ActorRefFactory}

import scala.collection.immutable

class ExampleActors {}

class ForwardingActor(next: ActorRef) extends Actor {
  def receive = {
    case msg => next ! msg
  }
}

class FilteringActor(next: ActorRef) extends Actor {
  def receive = {
    case msg: String => next ! msg
    case _           => None
  }
}

class SequencingActor(next: ActorRef, head: immutable.Seq[String],
                      tail: immutable.Seq[String]) extends Actor {
  def receive = {
    case msg =>
      head foreach { next ! _ }
      next ! msg
      tail foreach { next ! _ }
  }
}

class FailingActor() extends Actor {
  def receive = {
    case "fail" => throw new IllegalStateException("Uh oh!")
  }
}

class IncrementingActor() extends Actor {
  var count: Integer = 0

  def receive = {
    case "+" => count = count + 1
    case "-" => count = count - 1
  }
}

class ParentActor(childFactory: (ActorRefFactory) => ActorRef) extends Actor {
  def receive = {
    case Ping =>
      val childActor = childFactory(context)
      childActor ! Pong
  }
}

class ChildActor extends Actor {
  def receive = {
    case Ping =>
      context.parent ! Pong
  }
}
