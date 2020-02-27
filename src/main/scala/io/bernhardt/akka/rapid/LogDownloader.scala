package io.bernhardt.akka.rapid

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._
import scala.concurrent.duration._

object LogDownloader extends App {

  "mkdir logs".!

  ec2Api.foreach { api =>
    val instances = api.instances
      .filter(_.state.getName == "running")
      .map(_.publicDnsName)

    instances.grouped(20).foreach { group =>
      println(s"Downloading logs from ${group.mkString(" ")}")
      Await.result(Future.sequence(group.map { host =>
        Future {
          s"""scp -oStrictHostKeyChecking=no ubuntu@$host/tmp/akka-rapid-benchmark.log logs/$host.log""".!
        }
      }), 10.seconds)
    }

  }




}
