package com.netflix.spinnaker.tide.akka

import akka.actor.{ActorSystem, Props}
import akka.testkit._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import org.scalatest.DiagrammedAssertions

import scala.collection.immutable
import scala.concurrent.duration._

/**
 * a Test to show some TestKit examples
 */
@RunWith(classOf[JUnitRunner])
class TestKitUsageSpec
  extends TestKit(ActorSystem("TestKitUsageSpec"))
  with ImplicitSender
  with WordSpecLike with BeforeAndAfterAll with DiagrammedAssertions {

  override def afterAll {
    shutdown()
  }

  "EchoActor" should {
  val echoRef = TestActorRef(TestActors.echoActorProps)

    "respond with the same message it receives" in {
      within(50 millis) {
        echoRef ! Ping
        expectMsg(Ping)
      }
    }
  }

  "ForwardingActor" should {
    val forwardRef = TestActorRef(Props(classOf[ForwardingActor], testActor))

    "forward a message it receives" in {
      within(50 millis) {
        forwardRef ! Ping
        expectMsg(Ping)
      }
    }
  }

  "FilteringActor" should {
    val filterRef = TestActorRef(Props(classOf[FilteringActor], testActor))

    "filter ints" in {
      within(50 millis) {
        filterRef ! 1
        expectNoMsg()
      }
    }

    "not filter Strings" in {
      within(50 millis) {
        filterRef ! "test"
        expectMsg("test")
      }
    }

    "filter all messages, except Strings" in {
      within(50 millis) {
        filterRef ! "some"
        filterRef ! "more"
        filterRef ! 1
        filterRef ! "text"
        filterRef ! 1
      }

      val actual = receiveWhile(50 millis) {
        case msg => msg
      }

      assert(Seq("some", "more", "text") === actual)
    }
  }

  "A SequencingActor" when {
    val headList = immutable.Seq().padTo(3, "noise")
    val tailList = immutable.Seq().padTo(5, "noise")
    val seqRef = TestActorRef(Props(classOf[SequencingActor], testActor, headList, tailList))
    "initialized" should {

      "receive an interesting message at some point" in {
        within(50 millis) {
          ignoreMsg {
            case msg: String => msg == "noise"
          }
          seqRef ! Ping
          expectMsg(Ping)
          expectNoMsg()
          ignoreNoMsg()
        }
      }
    }
  }

  "A ParentActor" should {
    val child = TestProbe()
    val underTest = TestActorRef(new ParentActor(_ => child.ref))

    "delegate the important work to the client" in {
      within(50 millis) {
        underTest ! Ping
        child.expectMsg(Pong)
      }
    }
  }

  "A ChildActor" should {
    val parent = TestProbe()
    val underTest = TestActorRef(Props(classOf[ChildActor]), parent.ref, "ChildActor")

    "inform parent when told to do something" in {
      within(50 millis) {
        underTest ! Ping
        parent.expectMsg(Pong)
      }
    }
  }

}
