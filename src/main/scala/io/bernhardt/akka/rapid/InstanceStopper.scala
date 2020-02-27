package io.bernhardt.akka.rapid

/**
 * At scale, those t2 instances become really expensive
 */
object InstanceStopper extends App {

  ec2Api.foreach { api =>
    val instances = api.instances
      .filter(_.state.getName == "running")

    instances.grouped(50).foreach { group =>
      println(s"Stopping instances ${group.map(_.instanceId).mkString(" ")}")
      api.stop(group :_*)
    }

  }

}
