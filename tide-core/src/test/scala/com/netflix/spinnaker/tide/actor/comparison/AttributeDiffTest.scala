package com.netflix.spinnaker.tide.actor.comparison

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{DiagrammedAssertions, GivenWhenThen, FunSpec}

class AttributeDiffTest extends FunSpec with GivenWhenThen with DiagrammedAssertions with TableDrivenPropertyChecks {

  val attributeDiff = AttributeDiff.forIdentifierType(classOf[Int])

  case class Foo(i: Int, s: String, opt: Option[String], set: Set[String])

  describe("AttributeDiff") {

    it("should construct with initial object") {
      When("compareResource")
      val actual = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      Then("attributes are populated")
      assert(actual.allIdentifiers == Set(1))
      val expectedGroups = Set(AttributeGroup(Set(1), Map("i" -> 1, "s" -> "a", "opt" -> None, "set" -> Set())))
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare with same object") {
      val initialDiff = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      When("compareResource")
      val actual = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      Then("it replaces the original")
      assert(actual.allIdentifiers == Set(1))
      val expectedGroups = Set(AttributeGroup(Set(1), Map("i" -> 1, "s" -> "a", "opt" -> None, "set" -> Set())))
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare with completely different") {
      val initialDiff = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(2, Foo(2, "b", Some("b"), Set("asdf")))

      Then("it replaces the original")
      assert(actual.allIdentifiers == Set(1, 2))
      val expectedGroups = Set(
        AttributeGroup(Set(1), Map("i" -> 1, "s" -> "a", "opt" -> None, "set" -> Set())),
        AttributeGroup(Set(2), Map("i" -> 2, "s" -> "b", "opt" -> Some("b"), "set" -> Set("asdf")))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare with same object using new identifier") {
      val initialDiff = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(2, Foo(1, "a", None, Set()))

      Then("it is paired with the original")
      assert(actual.allIdentifiers == Set(1, 2))
      val expectedGroups = Set(AttributeGroup(Set(1, 2), Map("i" -> 1, "s" -> "a", "opt" -> None, "set" -> Set())))
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare with different object using same identifier") {
      val initialDiff = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(1, Foo(2, "a", None, Set()))

      Then("it replaces the original")
      assert(actual.allIdentifiers == Set(1))
      val expectedGroups = Set(AttributeGroup(Set(1), Map("i" -> 2, "s" -> "a", "opt" -> None, "set" -> Set())))
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare with different object using different identifier") {
      val initialDiff = attributeDiff.compareResource(1, Foo(1, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(2, Foo(2, "a", None, Set()))

      Then("it groups the common attributes")
      assert(actual.allIdentifiers == Set(1, 2))
      val expectedGroups = Set(
        AttributeGroup(Set(1, 2), Map("s" -> "a", "opt" -> None, "set" -> Set())),
        AttributeGroup(Set(1), Map("i" -> 1)),
        AttributeGroup(Set(2), Map("i" -> 2))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare a third object with repeated identifier") {
      val initialDiff = attributeDiff.
        compareResource(1, Foo(1, "a", None, Set())).
        compareResource(2, Foo(2, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(2, Foo(2, "b", None, Set()))

      Then("it replaces the original")
      assert(actual.allIdentifiers == Set(1, 2))
      val expectedGroups = Set(
        AttributeGroup(Set(1, 2), Map("opt" -> None, "set" -> Set())),
        AttributeGroup(Set(1), Map("i" -> 1, "s" -> "a")),
        AttributeGroup(Set(2), Map("i" -> 2, "s" -> "b"))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare a third object with same state and new identifier") {
      val initialDiff = attributeDiff.
        compareResource(1, Foo(1, "a", None, Set())).
        compareResource(2, Foo(2, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(3, Foo(1, "a", None, Set()))

      Then("it is added to attributes")
      assert(actual.allIdentifiers == Set(1, 2, 3))
      val expectedGroups = Set(
        AttributeGroup(Set(1, 2, 3), Map("s" -> "a", "opt" -> None, "set" -> Set())),
        AttributeGroup(Set(1, 3), Map("i" -> 1)),
        AttributeGroup(Set(2), Map("i" -> 2))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare a third object with different state and new identifier") {
      val initialDiff = attributeDiff.
        compareResource(1, Foo(1, "a", None, Set())).
        compareResource(2, Foo(2, "a", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(3, Foo(3, "b", None, Set()))

      Then("it is added to attributes")
      assert(actual.allIdentifiers == Set(1, 2, 3))
      val expectedGroups = Set(
        AttributeGroup(Set(1, 2, 3), Map("opt" -> None, "set" -> Set())),
        AttributeGroup(Set(1, 2), Map("s" -> "a")),
        AttributeGroup(Set(1), Map("i" -> 1)),
        AttributeGroup(Set(2), Map("i" -> 2)),
        AttributeGroup(Set(3), Map("i" -> 3, "s" -> "b"))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }

    it("should compare a third object with that shares state with each of the first two") {
      val initialDiff = attributeDiff.
        compareResource(1, Foo(1, "a", None, Set())).
        compareResource(2, Foo(2, "b", None, Set()))

      When("compareResource")
      val actual = initialDiff.compareResource(3, Foo(2, "a", None, Set()))

      Then("it is added to attributes")
      assert(actual.allIdentifiers == Set(1, 2, 3))
      val expectedGroups = Set(
        AttributeGroup(Set(1, 2, 3), Map("opt" -> None, "set" -> Set())),
        AttributeGroup(Set(2, 3), Map("i" -> 2)),
        AttributeGroup(Set(1, 3), Map("s" -> "a")),
        AttributeGroup(Set(1), Map("i" -> 1)),
        AttributeGroup(Set(2), Map("s" -> "b"))
      )
      assert(actual.attributeGroups.toSet == expectedGroups)
    }
  }

  it("should remove object") {
    val initialDiff = attributeDiff.
      compareResource(1, Foo(1, "a", None, Set())).
      compareResource(2, Foo(2, "b", None, Set())).
      compareResource(3, Foo(2, "a", None, Set()))

    When("removeResource")
    val actual = initialDiff.removeResource(2)

    Then("it is removed from attributes")
    assert(actual.allIdentifiers == Set(1, 3))
    val expectedGroups = Set(
      AttributeGroup(Set(1, 3), Map("opt" -> None, "set" -> Set())),
      AttributeGroup(Set(1, 3), Map("s" -> "a")),
      AttributeGroup(Set(3), Map("i" -> 2)),
      AttributeGroup(Set(1), Map("i" -> 1))
    )
    assert(actual.attributeGroups.toSet == expectedGroups)
  }
}