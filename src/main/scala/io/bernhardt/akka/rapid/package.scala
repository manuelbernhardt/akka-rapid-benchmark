package io.bernhardt.akka

import awscala.Region
import awscala.ec2.EC2

import scala.sys.process._

package object rapid {

  def shutdownMachineNow(): Unit = {
    "shutdown -fh now".run()
  }
  def shutdownMachineGracefully(): Unit = {
    "shutdown".run()
  }
  def partitionNow(): Unit = {
    "tc qdisc add dev ens5 root netem loss 100%".!
  }

  lazy val ec2Api = for {
    keyId <- sys.env.get("AWS_ACCESS_KEY").filterNot(_.trim.isEmpty)
    keySecret <- sys.env.get("AWS_SECRET_ACCESS_KEY").filterNot(_.trim.isEmpty)
    region <- sys.env.get("AWS_DEFAULT_REGION").filterNot(_.trim.isEmpty)
  } yield {
    EC2(keyId, keySecret)(Region(region))
  }



}
