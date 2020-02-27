package io.bernhardt.akka.rapid

import akka.event.slf4j.Logger
import awscala._
import ec2._
import scala.sys.process._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

object RemoteAction extends App {

  val log = Logger("traffic-blocker")

  val Instance = "instance"
  val System = "system"

  private val ec2Api = for {
    keyId <- sys.env.get("AWS_ACCESS_KEY").filterNot(_.trim.isEmpty)
    keySecret <- sys.env.get("AWS_SECRET_ACCESS_KEY").filterNot(_.trim.isEmpty)
    region <- sys.env.get("AWS_DEFAULT_REGION").filterNot(_.trim.isEmpty)
  } yield {
    EC2(keyId, keySecret)(Region(region))
  }

  if(args.length < 2) {
    println("Usage: io.bernhardt.akka.rapid.RemoteAction <type> <numNodes>")
    println(s"       type can be: $Instance / $System")
  } else {
    val actionType = args(0)
    val n = args(1).toInt

    ec2Api.foreach { api =>
      val instances = api.instances
        .filter(_.state.getName == "running")
        .filterNot(_.tags.exists(_._2 == "akka-template"))
        .filterNot(_.tags.exists(_._2 == "akka-seed"))

      val sample = instances.take(n)
      val dnsNames = sample.sortBy(_.privateDnsName).map(_.publicDnsName)

      dnsNames.foreach { host =>
        Future {
          println(s"Writing action $actionType file to $host")
          actionType match {
            case Instance =>
              s"""ssh -oStrictHostKeyChecking=no ubuntu@$host `echo "$actionType" > /tmp/akka-stop-instance`""".!
            case System =>
              s"""ssh -oStrictHostKeyChecking=no ubuntu@$host `echo "$actionType" > /tmp/akka-stop-system`""".!
          }
        }
      }
    }
  }



}
