package com.netflix.spinnaker.tide.config

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer

class KryoInit {
  def customize(kryo: Kryo): Unit  = {
    kryo.setDefaultSerializer(classOf[CompatibleFieldSerializer[_]])
  }
}
