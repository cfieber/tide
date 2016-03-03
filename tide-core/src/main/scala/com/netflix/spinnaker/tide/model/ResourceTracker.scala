package com.netflix.spinnaker.tide.model

import com.netflix.spinnaker.tide.actor.copy.DependencyCopyActor.VpcIds
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.model.ResourceTracker._

case class ResourceTracker(source: VpcLocation, target: VpcLocation, vpcIds: VpcIds) {

  private var targetResourcesRequired: Set[TargetResource[_ <: AwsIdentity]] = Set()
  private var targetResourcesFound: Set[TargetResource[_ <: AwsIdentity]] = Set()
  private var targetResourcesNonexistent: Set[TargetResource[_]] = Set()

  private var sourceByTarget: Map[TargetResource[_], SourceResource[_]] = Map()
  private var targetToReferencingSource: Map[TargetResource[_ <: AwsIdentity], AwsReference[_ <: AwsIdentity]] = Map()

  private var sourceSecurityGroupToId: Map[SourceResource[SecurityGroupIdentity], String] = Map()
  private var targetSecurityGroupToId: Map[TargetResource[SecurityGroupIdentity], String] = Map()

  val Source = QualifiedVpcId(source.location, vpcIds.source)
  val Target = QualifiedVpcId(target.location, vpcIds.target)

  def requiredSource(source: SourceResource[_ <: AwsIdentity], referencedBy: Option[AwsReference[_ <: AwsIdentity]]): Unit = {
    val target = transformToTarget(source)
    targetResourcesRequired += target
    sourceByTarget += (transformToTarget(source) -> source)
    referencedBy match {
      case None => Nil
      case Some(ref) => targetToReferencingSource += (target -> ref)
    }
  }

  def foundTarget(targetResource: TargetResource[_ <: AwsIdentity]): Unit = {
    targetResourcesFound += targetResource
  }

  def nonexistentTarget(targetResource: TargetResource[_]): Unit = {
    targetResourcesNonexistent += targetResource
  }

  def isRequiredSource(sourceResource: SourceResource[_ <: AwsIdentity]): Boolean = {
    val targetResource = transformToTarget(sourceResource)
    targetResourcesRequired.contains(targetResource)
  }

  def isFoundTarget(targetResource: TargetResource[_ <: AwsIdentity]): Boolean = {
    targetResourcesFound.contains(targetResource)
  }

  def isNonexistentTarget(targetResource: TargetResource[_]): Boolean = {
    targetResourcesNonexistent.contains(targetResource)
  }

  def transformToTarget[T <: AwsIdentity](sourceResource: SourceResource[T]): TargetResource[T] = {
    val targetIdentity: T = sourceResource.ref.identity match {
      case sourceIdentity: LoadBalancerIdentity =>
        sourceIdentity.forVpc(source.vpcName, target.vpcName).asInstanceOf[T]
      case sourceIdentity: SecurityGroupIdentity =>
        sourceIdentity.dropLegacySuffix.copy(vpcId = vpcIds.target).asInstanceOf[T]
    }
    TargetResource(AwsReference(source.location, targetIdentity))
  }

  def getSourceByTarget[T <: AwsIdentity](targetResource: TargetResource[T]): SourceResource[T] = {
    sourceByTarget(targetResource).asInstanceOf[SourceResource[T]]
  }

  def dependenciesNotYetFound: Set[TargetResource[_ <: AwsIdentity]] = {
    targetResourcesRequired.diff(targetResourcesFound)
  }

  def lookupReferencingSourceByTarget(targetResource: TargetResource[_ <: AwsIdentity]): Option[AwsReference[_]] = {
    targetToReferencingSource.get(targetResource)
  }

  def addSourceSecurityGroupId(sourceResource: SourceResource[SecurityGroupIdentity], id: String) = {
    sourceSecurityGroupToId += (sourceResource -> id)
  }

  def addTargetSecurityGroupId(targetResource: TargetResource[SecurityGroupIdentity], id: String) = {
    targetSecurityGroupToId += (targetResource -> id)
  }

  def hasSecuritySourceGroupIdFor(sourceResource: SourceResource[SecurityGroupIdentity]) = {
    sourceSecurityGroupToId.contains(sourceResource)
  }

  def unseenSourceSecurityGroupReferences: Seq[AwsReference[SecurityGroupIdentity]] = {
    val sourceIdentities = sourceByTarget.values.map(_.ref.identity).toList
    val sourceSecurityGroupIdentities = sourceIdentities.filter(_.isInstanceOf[SecurityGroupIdentity]).
      asInstanceOf[Seq[SecurityGroupIdentity]]
    val allSourceSecurityGroupNames = sourceSecurityGroupIdentities.map(_.groupName)
    val seenSourceSecurityGroupNames = sourceSecurityGroupToId.keys.toList
    allSourceSecurityGroupNames.diff(seenSourceSecurityGroupNames).map { unseenSourceSecurityGroupName =>
      asSourceSecurityGroupReference(unseenSourceSecurityGroupName).ref
    }
  }

  def hasResolvedEverythingRequired: Boolean = {
    val identifiedTargets = targetSecurityGroupToId.keySet
    val identifiedTargetsFromSource = sourceSecurityGroupToId.keySet.map { sourceResource =>
      transformToTarget(sourceResource)
    }
    dependenciesNotYetFound.isEmpty && identifiedTargets == identifiedTargetsFromSource
  }

  def securityGroupIdsSourceToTarget: Map[String, String] = {
    sourceSecurityGroupToId.keySet.map { sourceResource =>
      val targetResource = transformToTarget(sourceResource)
      sourceSecurityGroupToId(sourceResource) -> targetSecurityGroupToId(targetResource)
    }.toMap
  }

  def asResource[T <: AwsIdentity](ref: AwsReference[T]): Resource[T] = {
    ref.identity match {
      case identity: SecurityGroupIdentity =>
        QualifiedVpcId(ref.location, identity.vpcId) match {
          case Target => TargetResource(ref)
          case Source => SourceResource(ref)
          case _ => Unknown(ref)
        }
      case identity: LoadBalancerIdentity =>
        if (identity.isConsistentWithVpc(target.vpcName)) {
          TargetResource(ref)
        } else {
          SourceResource(ref)
        }
      case _ => Unknown(ref)
    }
  }

  def asSourceSecurityGroupReference(name: String): SourceResource[SecurityGroupIdentity] = {
    SourceResource(AwsReference(source.location, SecurityGroupIdentity(name, vpcIds.source)))
  }

  def asSourceLoadBalancerReference(name: String): SourceResource[LoadBalancerIdentity] = {
    SourceResource(AwsReference(source.location, LoadBalancerIdentity(name)))
  }

}

object ResourceTracker {
  case class QualifiedVpcId(location: AwsLocation, vpcId: Option[String])

  sealed trait Resource[T <: AwsIdentity] {
    def ref: AwsReference[T]
  }
  case class TargetResource[T <: AwsIdentity](ref: AwsReference[T]) extends Resource[T]
  case class SourceResource[T <: AwsIdentity](ref: AwsReference[T]) extends Resource[T]
  case class Unknown[T <: AwsIdentity](ref: AwsReference[T]) extends Resource[T]
}