package com.netflix.spinnaker.tide.akka

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpecLike
import org.scalatest.DiagrammedAssertions
import org.scalatest.junit.JUnitRunner

/**
 * a Test to show some TestActorRef examples
 */
@RunWith(classOf[JUnitRunner])
class TestActorRefUsageSpec extends WordSpecLike with MockFactory with DiagrammedAssertions {

  implicit val system = ActorSystem("MyActorSystem", ConfigFactory.load())

  "IncrementingActor" should {
    val incrementingActorRef = TestActorRef(new IncrementingActor())

    "not change state for other values" in {
      incrementingActorRef ! "test"
      assert(incrementingActorRef.underlyingActor.count === 0)
    }

    "increment state for +" in {
      incrementingActorRef.underlyingActor.count = 2
      incrementingActorRef ! "+"
      assert(incrementingActorRef.underlyingActor.count === 3)
    }

    "decrement state for +" in {
      incrementingActorRef.underlyingActor.count = 9
      incrementingActorRef ! "-"
      assert(incrementingActorRef.underlyingActor.count === 8)
    }
  }

  "A FailingActor" when {
    val failingRef = TestActorRef(new FailingActor())
    "initialized" should {

      "pass" in {
        failingRef.receive("pass")
      }

      "fail" in {
        val t = intercept[IllegalStateException] { failingRef.receive("fail") }
        assert(t.getMessage === "Uh oh!")
      }
    }
  }

}

