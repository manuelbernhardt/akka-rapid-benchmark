package io.bernhardt.akka.rapid

object InstanceCounter extends App {

    ec2Api.foreach { api =>
      println(api.instances.size)
    }


}
