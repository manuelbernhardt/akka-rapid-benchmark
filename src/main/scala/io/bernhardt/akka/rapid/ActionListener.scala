package io.bernhardt.akka.rapid

import java.io.File

import akka.actor.{Actor, ActorLogging, Props, RootActorPath, Timers}
import akka.cluster.Cluster

import scala.sys.process._
import scala.concurrent.duration._
import akka.pattern.pipe

class ActionListener(disableSafetyStop: Boolean) extends Actor with ActorLogging with Timers {
  import ActionListener._
  import context.dispatcher

  timers.startTimerWithFixedDelay(Tick, Tick, 5.second)
  if (!disableSafetyStop) {
    timers.startSingleTimer(SafetyStop, SafetyStop, 30.minutes)
  }

  val instanceStopperFlagFile = new File("/tmp/akka-stop-instance")
  val systemStopperFlagFile = new File("/tmp/akka-stop-system")
  val allInstanceStopper = new File("/tmp/akka-stop-all")

  def receive = {
    case Tick =>
      if(instanceStopperFlagFile.exists()) {
        instanceStopperFlagFile.delete()
        log.info("== Killing instance when the next minute starts")
        shutdownMachineNow()
      }
      if(systemStopperFlagFile.exists()) {
        systemStopperFlagFile.delete()
        log.info("== Shutting down actor system when the next minute starts")
        context.system.terminate() pipeTo self
      }
      if(allInstanceStopper.exists()) {
        allInstanceStopper.delete()
        context.actorSelection("/user/coordinator") ! StartupCoordinator.StopAll
        val cluster = Cluster(context.system)
        val allAddresses = cluster.state.members.map(_.address).filterNot(_ == cluster.selfAddress)
        log.info("== Stopping {} instances without waiting", allAddresses.size)
        allAddresses.foreach { address =>
          context.actorSelection(RootActorPath(address) / "user" / "listener") ! Stop
        }
        self ! Stop
      }
    case Stop =>
      log.info("Shutting down instance gracefully")
      "shutdown".!
    case Kill =>
      log.info("Killing instance")
      shutdownMachineNow()
    case SafetyStop =>
      log.info("Shutting down after 10 minutes")
      shutdownMachineNow()

  }

}


object ActionListener {
  case object Tick
  final val Stop = "Stop"
  final val Kill = "Kill"
  case object SafetyStop

  def props(disableSafetyStop: Boolean) = Props(new ActionListener(disableSafetyStop))
}
