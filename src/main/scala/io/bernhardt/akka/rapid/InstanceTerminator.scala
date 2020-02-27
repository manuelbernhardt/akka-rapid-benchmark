package io.bernhardt.akka.rapid

/**
 * At scale, those t2 instances become really expensive
 */
object InstanceTerminator extends App {

  ec2Api.foreach { api =>
    api.instances.grouped(50).foreach { group =>
      println(s"Stopping instances ${group.map(_.instanceId).mkString(" ")}")
      api.terminate(group :_*)
    }
  }

}
