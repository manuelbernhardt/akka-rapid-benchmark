package io.bernhardt.akka.rapid

object SeedNodeAddress extends App {

  ec2Api.foreach { api =>
    api.instances.filter(_.tags.exists(_._2 == "akka-seed")).find(_.state.getName == "running").map { instance =>
      val name = instance.publicDnsName.trim
      if (name.isEmpty) {
        println("Not ready")
      } else {
        println(name)
      }
    } getOrElse {
      println("Not found")
    }

  }

}