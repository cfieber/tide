package com.netflix.spinnaker.tide.model

class LoadBalancerNameShortener {

  def shorten(name: String, loadBalancerNameSizeLimit: Int): String = {
    var abbreviateIfNeeded: String = name

    if (abbreviateIfNeeded.length > loadBalancerNameSizeLimit) {
      abbreviateIfNeeded = abbreviateIfNeeded.
        replace("-internal", "-int").
        replace("-external", "-ext").
        replace("-elb", "")
    }

    if (abbreviateIfNeeded.length > loadBalancerNameSizeLimit) {
      abbreviateIfNeeded = abbreviateIfNeeded.
        replace("-dev", "-d").
        replace("-test", "-t").
        replace("-prod", "-p").
        replace("-main", "-m").
        replace("-classic", "-c").
        replace("-legacy", "-l").
        replace("-backend", "-b").
        replace("-frontend", "-f").
        replace("-front", "-f").
        replace("-release", "-r").
        replace("-private", "-p").
        replace("-edge", "-e").
        replace("-global", "-g").
        replace("-vpc", "")
    }

    if (abbreviateIfNeeded.length > loadBalancerNameSizeLimit) {
      abbreviateIfNeeded = abbreviateIfNeeded.
        replace("internal", "int").
        replace("external", "ext").
        replace("backend", "b").
        replace("frontend", "f").
        replace("east", "e").
        replace("west", "w").
        replace("north", "n").
        replace("south", "s")
    }

    if (abbreviateIfNeeded.length > loadBalancerNameSizeLimit) {
      val excessLength = abbreviateIfNeeded.length - loadBalancerNameSizeLimit
      abbreviateIfNeeded.dropRight(excessLength)
    } else {
      abbreviateIfNeeded
    }
  }
}
