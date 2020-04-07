package io.bernhardt.akka.rapid

import akka.event.slf4j.Logger
import awscala._
import ec2._
import scala.sys.process._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

object RemoteAction extends App {

  val log = Logger("remote-action")

  val Instance = "instance"
  val System = "system"
  val Kill = "kill"
  val StopAll = "stop-all"

  private val ec2Api = for {
    keyId <- sys.env.get("AWS_ACCESS_KEY").filterNot(_.trim.isEmpty)
    keySecret <- sys.env.get("AWS_SECRET_ACCESS_KEY").filterNot(_.trim.isEmpty)
    region <- sys.env.get("AWS_DEFAULT_REGION").filterNot(_.trim.isEmpty)
  } yield {
    EC2(keyId, keySecret)(Region(region))
  }

  if(args.length < 1) {
    println("Usage: io.bernhardt.akka.rapid.RemoteAction TYPE [NUM_NODES] [HOSTNAME_1 HOSTNAME_2 HOSTNAME_N ...>)")
    println(s"       TYPE can be: $Instance / $System / $StopAll")
    println(s"       specify one or more hostnames to only apply to that set of hosts")
  } else {
    val actionType = args(0)
    val n = if (args.length > 1) args(1).toInt else 0
    val givenHosts: Option[Iterable[String]] = if(args.length > 2) Option(args.drop(2).map(_.trim)) else None

    ec2Api.foreach { api =>

      val dnsNames: Iterable[String] = givenHosts.getOrElse {
        val instances = api.instances
          .filter(_.state.getName == "running")
          .filterNot(_.tags.exists(_._2 == "akka-template"))
          .filterNot(_.tags.exists(_._2 == "akka-seed"))
          .filterNot(_.tags.exists(_._2.startsWith("akka-broadcaster")))
          .filterNot(i => i.publicDnsName == null)
          .filterNot(_.publicDnsName.trim.isEmpty)

        val sample = instances.take(n)
        sample.sortBy(_.privateDnsName).map(_.publicDnsName)
      }

      if (actionType == StopAll) {
        api.instances.filter(_.tags.exists(_._2 == "akka-seed")).find(_.state.getName == "running").foreach { seed =>
          s"""ssh -oStrictHostKeyChecking=no ubuntu@${seed.publicDnsName} `echo "$actionType" > /tmp/akka-stop-all`""".!
        }
      }

      dnsNames.foreach { host =>
        Future {
          println(s"Scheduling action $actionType at $host")
          actionType match {
            case Instance =>
              s"""ssh -oStrictHostKeyChecking=no ubuntu@$host `echo "$actionType" > /tmp/akka-stop-instance`""".!
            case System =>
              s"""ssh -oStrictHostKeyChecking=no ubuntu@$host `echo "$actionType" > /tmp/akka-stop-system`""".!
            case Kill =>
              s"""ssh -oStrictHostKeyChecking=no ubuntu@$host `sudo shutdown -fh now`""".!
            case unknown =>
              println(s"Unknown action $unknown")
          }
        }
      }
    }
  }



}
