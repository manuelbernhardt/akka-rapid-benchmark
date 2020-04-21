package io.bernhardt.akka.rapid

import java.io.File

import akka.actor.{Actor, ActorLogging, Props, Timers}
import akka.cluster.{Cluster, MemberStatus}

import scala.concurrent.duration._

class MembershipRecorder(expectedCount: Int) extends Actor with Timers with ActorLogging {
  import MembershipRecorder._

  val cluster = Cluster(context.system)

  timers.startTimerAtFixedRate(Tick, Tick, 5.seconds)

  var countWasReached = false

  override def receive: Receive = {
    case Tick =>
      val memberCount = cluster.state.members.count(_.status == MemberStatus.Up)
      if (memberCount >= expectedCount) {
        log.info(s"""{"memberCount": $memberCount, "reachedCount": true}""")
        if (!countWasReached) {
          new File(s"/tmp/akka-rapid-reached-size-${cluster.selfAddress.host.get}-${cluster.selfAddress.port.get}").createNewFile()
        }
        countWasReached = true
      } else {
        log.info(s"""{"memberCount": $memberCount}""")
      }
  }
}

object MembershipRecorder {
  case object Tick

  def props(expectedCount: Int) = Props(new MembershipRecorder(expectedCount))
}

