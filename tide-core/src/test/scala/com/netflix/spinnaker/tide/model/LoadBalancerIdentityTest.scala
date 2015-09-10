package com.netflix.spinnaker.tide.model

import com.netflix.spinnaker.tide.model.AwsApi.LoadBalancerIdentity
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSpec, DiagrammedAssertions, GivenWhenThen}

class LoadBalancerIdentityTest extends FunSpec with GivenWhenThen with DiagrammedAssertions with TableDrivenPropertyChecks {

  val loadBalancerNameTransformations = Table(
    ("loadBalancerName", "vpc", "expectedLoadBalancerName"),
    ("snowcrash", "Main", "snowcrash-Main"),
    ("diamondage--frontend", "zero", "diamondage--zero"),
    ("cryptonomicon-frontend", "vpc1", "cryptonomicon-vpc1"),
    ("quicksilver-internal-frontend", "secure", "quicksilver-internal-secure"),
    ("seveneves-internal-frontend-2-backend", "vpc0", "seveneves-i-f-2-b-vpc0"),
    ("ayounglandysillustratedprimer", "vpc0", "ayounglandysillustratedprim-vpc0")
  )

  describe("LoadBalancerIdentity") {
    it("should transform load balancer name to reflect vpc but not go over 32 characters") {
      forAll(loadBalancerNameTransformations) { (loadBalancerName, vpc, expectedLoadBalancerName) =>
        val actualLoadBalancerName = LoadBalancerIdentity(loadBalancerName).forVpc(Option(vpc)).loadBalancerName
        assert(actualLoadBalancerName == expectedLoadBalancerName)
      }
    }
  }

}
