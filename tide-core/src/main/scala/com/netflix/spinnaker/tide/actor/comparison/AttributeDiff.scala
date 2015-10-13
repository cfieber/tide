package com.netflix.spinnaker.tide.actor.comparison

case class AttributeDiff[T] private[AttributeDiff] (allIdentifiers: Set[T],
                                                    attributeGroups: Seq[AttributeGroup[T]]) {

  def removeResource(newIdentifier: T): AttributeDiff[T] = {
    AttributeDiff(allIdentifiers - newIdentifier, AttributeDiff.removeIdentifierFromAttributeGroups(newIdentifier, attributeGroups))
  }

  def compareResource(newIdentifier: T, resource: Product): AttributeDiff[T] = {
    val attributeGroupsWithIdentifierRemoved = AttributeDiff.
      removeIdentifierFromAttributeGroups(newIdentifier, attributeGroups)
    var unmatchedResourceAttributes = AttributeDiff.constructAttributeMap(resource)
    var identifiersToCommonAttributes: Map[Set[T], Map[String, Any]] = attributeGroupsWithIdentifierRemoved.map { it =>
      it.identifiers -> it.commonAttributes
    }.toMap
    attributeGroupsWithIdentifierRemoved.foreach { attributeGroup =>
      val intersection = attributeGroup.commonAttributes.toSet.intersect(unmatchedResourceAttributes.toSet).toMap
      if (intersection.nonEmpty) {
        unmatchedResourceAttributes = unmatchedResourceAttributes -- intersection.keys
        identifiersToCommonAttributes += (attributeGroup.identifiers + newIdentifier -> intersection)
        val existingAttributesForCurrentIdentifiers: Map[String, Any] = identifiersToCommonAttributes.
          getOrElse(attributeGroup.identifiers, Map())
        val attributesForExistingIdentifiers: Map[String, Any] = (attributeGroup.commonAttributes ++ existingAttributesForCurrentIdentifiers)-- intersection.keys
        if (attributesForExistingIdentifiers.nonEmpty) {
          identifiersToCommonAttributes += (attributeGroup.identifiers -> attributesForExistingIdentifiers)
        } else {
          identifiersToCommonAttributes -= attributeGroup.identifiers
        }
      }
    }
    if (unmatchedResourceAttributes.nonEmpty) {
      identifiersToCommonAttributes += (Set(newIdentifier) -> unmatchedResourceAttributes)
    }
    var newAttributeGroups: Seq[AttributeGroup[T]] = identifiersToCommonAttributes.map {
      case (identifiers, commonAttributes) => AttributeGroup(identifiers, commonAttributes)
    }.toList
    val sortedNewAttributeGroups = newAttributeGroups.sortBy(_.identifiers.size).reverse
    AttributeDiff(allIdentifiers + newIdentifier, sortedNewAttributeGroups)
  }

}

object AttributeDiff {

  def forIdentifierType[T](identifierType: Class[T]): AttributeDiff[T] = { new AttributeDiff[T](Set(), Nil) }

  def constructAttributeMap(o: Product): Map[String, Any] = {
    o.getClass.getDeclaredFields.map(_.getName).zip(o.productIterator.to).toMap
  }

  def removeIdentifierFromAttributeGroups[T](identifier: T,
                                             attributeGroups: Seq[AttributeGroup[T]]): Seq[AttributeGroup[T]] = {
    attributeGroups.map { attributeGroup =>
      attributeGroup.copy(identifiers = attributeGroup.identifiers - identifier)
    }.filter(_.identifiers.nonEmpty)
  }

}

case class AttributeGroup[T](identifiers: Set[T], commonAttributes: Map[String, Any] = Map())
