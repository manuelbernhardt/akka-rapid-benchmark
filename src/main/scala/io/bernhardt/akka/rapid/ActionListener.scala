package io.bernhardt.akka.rapid

import java.io.File

import akka.actor.{Actor, ActorLogging, Props, Timers}

import scala.sys.process._
import scala.concurrent.duration._
import akka.pattern.pipe

class ActionListener extends Actor with ActorLogging with Timers {
  import ActionListener._
  import context.dispatcher

  timers.startTimerWithFixedDelay(Tick, Tick, 1.second)

  val instanceStopperFlagFile = new File("/tmp/akka-stop-instance")
  val systemStopperFlagFile = new File("/tmp/akka-stop-system")

  def receive = {
    case Tick =>
      if(instanceStopperFlagFile.exists()) {
        instanceStopperFlagFile.delete()
        log.info("== Shutting down instance when the next minute starts")
        waitUntilNextMinute()
        "shutdown -fh now".!
      }
      if(systemStopperFlagFile.exists()) {
        systemStopperFlagFile.delete()
        log.info("== Shutting down actor system when the next minute starts")
        waitUntilNextMinute()
        context.system.terminate() pipeTo self

      }
  }

}


object ActionListener {
  case object Tick
  case object Tock

  def props() = Props(new ActionListener)
}
