package io.bernhardt.akka

import java.time.Instant
import java.time.temporal.ChronoUnit

import awscala.Region
import awscala.ec2.EC2

package object rapid {

  def waitUntilNextMinute(): Unit = {
    val nextMinute = Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
    Thread.sleep(nextMinute.toEpochMilli - Instant.now().toEpochMilli)
  }

  def shutdownMachine(): Unit = {
    import scala.sys.process._
    "shutdown -fh now".run()
  }

  lazy val ec2Api = for {
    keyId <- sys.env.get("AWS_ACCESS_KEY").filterNot(_.trim.isEmpty)
    keySecret <- sys.env.get("AWS_SECRET_ACCESS_KEY").filterNot(_.trim.isEmpty)
    region <- sys.env.get("AWS_DEFAULT_REGION").filterNot(_.trim.isEmpty)
  } yield {
    EC2(keyId, keySecret)(Region(region))
  }



}
