package io.bernhardt.akka.rapid

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, RootActorPath, Timers}
import akka.cluster.{Cluster, ClusterEvent, MemberStatus}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

/**
 * (No longer) Simplistic register to keep track of started JVMs so that we can bootstrap clustering at roughly the same time.
 *
 * This should've been coded as a state machine, but Forde's tenth rule applies
 */
class StartupCoordinationServer(interface: String, expectedMemberCount: Int, broadcasterCount: Int)(implicit system: ActorSystem) {
  import StartupCoordinator._

  implicit val askTimeout = Timeout(1.second)

  val coordinator = {
    system.actorOf(Props(new StartupCoordinator(expectedMemberCount, broadcasterCount)), "coordinator")
  }

  val route =
    concat(
      path("register" / ".*".r) { host =>
        post {
          onComplete(coordinator ? Register(host, isBroadcaster = false)) {
            case Success(Registered) => complete(OK)
            case Success(GoAway) => complete(EnhanceYourCalm)
            case Success(_) | Failure(_) => complete(InternalServerError)
          }
        }
      },
      path("registerBroadcaster" / ".*".r) { host =>
        post {
          onComplete(coordinator ? Register(host, isBroadcaster = true)) {
            case Success(Registered) => complete(OK)
            case Success(_) | Failure(_) => complete(InternalServerError)
          }
        }
      }

    )

  Http().bindAndHandle(route, interface, 2020)

}

class StartupCoordinator(expectedMemberCount: Int, broadcasterCount: Int) extends Actor with Timers with ActorLogging {
  import StartupCoordinator._
  import context.dispatcher
  implicit val mat = context.system

  val http = Http()(context.system)

  val expectedJoiningCount = expectedMemberCount - broadcasterCount - 1

  var hasStarted = false

  var registeredBroadcasters = Set.empty[String]
  var registeredHosts = Set.empty[String]
  var availableHosts = Set.empty[String]
  var joiningHosts = Set.empty[String]
  var lateJoiningHosts = Set.empty[String]
  var joinedHosts = Set.empty[String]
  var seedAndBroadcasters = Set.empty[String]

  var batchStartTime = -1L
  var batchSize = 0
  var lastAddedMemberTime = System.currentTimeMillis()
  var hasStartedBroadcasters = false

  var progressTimeout = InitialProgressTimeout
  var batchInterval = InitialBatchInterval

  context.system.eventStream.subscribe(self, classOf[MemberEvent])

  val cluster = Cluster(context.system)


  def receive: Receive = {
    case Register(host, isBroadcaster) =>
      handleRegister(host, isBroadcaster)
    case NextBatch =>
      handleNextBatch()
    case up: MemberUp =>
      handleMemberUp(up)
    case ProgressTick =>
      handleProgressTick()
    case KillOnePercent =>
      handleKillOnePercent()
  }

  def waitingForJoiners: Receive = {
    case Register(host, isBroadcaster) =>
      handleRegister(host, isBroadcaster)
    case up: MemberUp =>
      handleMemberUp(up)
    case ProgressTick =>
      handleProgressTick()
    case KillOnePercent =>
      handleKillOnePercent()
  }

  override def unhandled(message: Any): Unit = message match {
    case StopAll => handleStopAll()
    case _: MemberEvent => // ignore
    case other => super.unhandled(other)
  }

  private def handleRegister(host: String, isBroadcaster: Boolean): Unit = {
    if (!hasStarted && registeredHosts.size > expectedJoiningCount + SpareHostsLimit || hasStarted && registeredHosts.size > SpareHostsLimit) {
      sender() ! GoAway
      log.debug("Surplus node {} registered, told to go away", host)
    } else {
      if (isBroadcaster) {
        registeredBroadcasters += host
        sender() ! Registered
        log.info("Node {} registered, total of {} unique broadcasters", host, registeredBroadcasters.size)
      } else {
        registeredHosts += host
        sender() ! Registered
        val size = if(hasStarted) expectedJoiningCount + registeredHosts.size else registeredHosts.size
        if (size > 0 && size % 10 == 0) {
          log.info("Node {} registered, total of {} unique nodes", host, size)
        }
      }
    }

    if (!hasStartedBroadcasters && registeredBroadcasters.size == broadcasterCount) {
      log.info("Starting {} broadcasters", broadcasterCount)
      // OK to form the initial cluster
      registeredBroadcasters.foreach { host =>
        sendRequest(host, "ready")
      }
      hasStartedBroadcasters = true
    }

    val enoughNodes = registeredHosts.size >= expectedJoiningCount
    val enoughSeedNodesAndBroadcasters = cluster.state.members.size >= broadcasterCount + 1
    val hasAlreadyStarted = batchStartTime > 0
    val okToJoin = enoughNodes && enoughSeedNodesAndBroadcasters && !hasAlreadyStarted
    if (okToJoin) {
      hasStarted = true
      log.info("Enough nodes and all broadcasters available, starting now")
      availableHosts ++= registeredHosts
      registeredHosts = Set.empty
      nextBatch(InitialBatchSize)
    }
  }

  private def handleNextBatch(): Unit = {
    timers.startTimerWithFixedDelay(ProgressTick, ProgressTick, progressTimeout)
    if (availableHosts.isEmpty && joinedHosts.size < expectedJoiningCount) {
      // everyone should be here, but they aren't
      // likely the results of small partitions (individual instances can get partitioned)
      // ask for backup
      log.warning("Taking {} hosts from surplus", expectedJoiningCount - joinedHosts.size)
      val more = registeredHosts.take(expectedJoiningCount - joinedHosts.size)
      registeredHosts --= more
      availableHosts ++= more
    }
    nextBatch(IncrementalBatchSize)
  }

  private def handleMemberUp(up: ClusterEvent.MemberUp): Unit = {
    up.member.address.host.foreach { host =>
      lastAddedMemberTime = System.currentTimeMillis()
      if (joiningHosts.contains(host) || lateJoiningHosts.contains(host)) {
        joinedHosts += host
        joiningHosts -= host
        lateJoiningHosts -= host

        if(joiningHosts.isEmpty) {
          val duration = FiniteDuration(System.currentTimeMillis() - batchStartTime, TimeUnit.MILLISECONDS)
          timers.cancel(ProgressTick)
          log.info("Batch of size {} took {} seconds to complete, {} total members in cluster",
            batchSize,
            duration.toSeconds,
            joinedHosts.size + seedAndBroadcasters.size
          )
          if(joinedHosts.size < expectedJoiningCount) {
            timers.startSingleTimer(NextBatch, NextBatch, batchInterval)
            context.become(receive)
          }
        }
        if (joinedHosts.size == expectedJoiningCount) {
          log.info("REACHED TARGET SIZE of {}!!!!", joinedHosts.size + seedAndBroadcasters.size + 1)
          log.info("Scheduling kill of 1% in 1 minute")
          timers.cancel(ProgressTick)
          timers.startSingleTimer(KillOnePercent, KillOnePercent, 1.minute)
        }
        log.debug("Host {} joined, total of {} joined hosts and {} joining", host, joinedHosts.size, joiningHosts.size)
      } else {
        // seed (this node) or broadcasting nodes
        seedAndBroadcasters += host
      }
    }
  }

  private def handleProgressTick(): Unit = {
    if (joinedHosts.nonEmpty && joinedHosts.size < expectedJoiningCount && (System.currentTimeMillis() - lastAddedMemberTime) > InitialProgressTimeout.toMillis) {
      log.info("Forcing progress after {} s, moving {} timed out joiners to late joiners, kicking out {} late joiners, currently joined: {}",
        progressTimeout.toSeconds, joiningHosts.size, lateJoiningHosts.size, joinedHosts.size + seedAndBroadcasters.size)
      lateJoiningHosts.foreach { host =>
        sendRequest(host, "timeout")
      }
      lateJoiningHosts = Set.empty
      lateJoiningHosts ++= joiningHosts
      joiningHosts = Set.empty
      batchInterval = FiniteDuration((batchInterval.toSeconds * 1.5).toInt, TimeUnit.SECONDS)
      progressTimeout = FiniteDuration((progressTimeout.toSeconds * 1.2).toInt, TimeUnit.SECONDS)
      self ! NextBatch
      context.become(receive)
    }
  }

  private def handleKillOnePercent(): Unit = {
    val onePercent = expectedMemberCount / 100
    val candidates = Cluster(context.system).state.members
      .filter(_.status == MemberStatus.Up)
      .filterNot(m => m.hasRole("seed") || m.hasRole("broadcaster"))
      .map(_.address)
    val victims = Random.shuffle(candidates).take(onePercent)
    log.info("Sending kill message to {} instances", onePercent)
    log.info(victims.map(_.host.get).mkString(" "))
    victims.foreach { victim =>
      sendRequest(victim.host.get, "leave")
    }
  }

  private def handleStopAll(): Unit = {
    log.info("Shutting down everyone")
    (registeredHosts ++ joiningHosts ++ joinedHosts ++ availableHosts ++ seedAndBroadcasters).foreach { host =>
      sendRequest(host, "leaveGracefully")
    }
  }

  private def nextBatch(size: Int): Unit = {
    batchSize = size
    log.info("Allowing batch of {} nodes to join after {} s", size, batchInterval.toSeconds)
    batchStartTime = System.currentTimeMillis()
    val batch = availableHosts.take(size)
    availableHosts = availableHosts.diff(batch)
    joiningHosts ++= batch
    joiningHosts.foreach { host =>
      sendRequest(host, "ready")
    }
    context.become(waitingForJoiners)
  }

  private def sendRequest(host: String, path: String): Unit = {
    http.singleRequest(HttpRequest(
      method = HttpMethods.POST,
      uri = s"http://$host:2030/$path")
    ).foreach {
      case response if response.status == StatusCodes.OK =>
        response.discardEntityBytes()
      case failure =>
        log.error("Host {} responded with {}", host, failure.status)
        failure.discardEntityBytes()
    }
  }
}

object StartupCoordinator {
  final case class Register(host: String, isBroadcaster: Boolean)
  sealed trait Registration
  final case object Registered extends Registration
  final case object GoAway extends Registration

  final case object NextBatch
  final case object ProgressTick

  final case object KillOnePercent

  final case object StopAll

  val InitialBatchSize = 500
  val IncrementalBatchSize = 500
  val SpareHostsLimit = 1500


  val InitialBatchInterval = 15.seconds
  val InitialProgressTimeout = 45.seconds

  val ProgressCheckInterval = 5.seconds

}