package io.bernhardt.akka.rapid

import akka.actor.{Actor, ActorLogging, Props, Timers}
import akka.cluster.{Cluster, MemberStatus}

import scala.concurrent.duration._

class MembershipRecorder(expectedCount: Int) extends Actor with Timers with ActorLogging {
  import MembershipRecorder._

  val cluster = Cluster(context.system)

  timers.startTimerAtFixedRate(Tick, Tick, 2.seconds)

  override def receive: Receive = {
    case Tick =>
      val memberCount = cluster.state.members.count(_.status == MemberStatus.Up)
      log.info(s"""{"memberCount": $memberCount}""")
  }
}

object MembershipRecorder {
  case object Tick

  def props(expectedCount: Int) = Props(new MembershipRecorder(expectedCount))
}

