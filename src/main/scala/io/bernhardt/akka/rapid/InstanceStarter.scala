package io.bernhardt.akka.rapid

import scala.collection.JavaConverters._

/**
 * At scale, those t2 instances become really expensive
 */
object InstanceStarter extends App {

  ec2Api.foreach { api =>
    val instances = api.instances
      .filter(_.state.getName == "stopped")

    val seed = instances.find(_.tags.exists { case (k, v) => v == "akka-seed"} )
    seed.foreach { s =>
      println("Starting seed...")
      api.start(s)
      Thread.sleep(10000)
    }

    instances.filterNot(i => seed.contains(i)).grouped(50).foreach { group =>
      println(s"Starting instances ${group.map(_.instanceId).mkString(" ")}")
//      val starting = api.start(group :_*).getStartingInstances
//      var startingCount = starting.size()
//      while (startingCount > 0) {
//        Thread.sleep(1000)
//        startingCount = api.instances(group.map(_.instanceId)).count(_.state.getName != "running")
//      }
      Thread.sleep(5000)
    }

  }

}
