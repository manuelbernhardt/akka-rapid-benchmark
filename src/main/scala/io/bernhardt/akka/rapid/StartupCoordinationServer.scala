package io.bernhardt.akka.rapid

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, RootActorPath, Timers}
import akka.cluster.{Cluster, MemberStatus}
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
 * (No longer) Simplistic register to keep track of started JVMs so that we can bootstrap clustering at roughly the same time
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
          onComplete(coordinator ? Register(host)) {
            case Success(Registered) => complete(OK)
            case Success(GoAway) => complete(EnhanceYourCalm)
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

  var registeredHosts = Set.empty[String]
  var availableHosts = Set.empty[String]
  var joiningHosts = Set.empty[String]
  var joinedHosts = Set.empty[String]
  var seedNodes = Set.empty[String]

  var batchStartTime = -1L
  var batchSize = 0
  var lastAddedMemberTime = System.currentTimeMillis()

  var progressTimeout = InitialProgressTimeout

  context.system.eventStream.subscribe(self, classOf[MemberEvent])


  def receive = {
    case Register(host) =>
      if (registeredHosts.size > expectedJoiningCount + SpareHostsLimit) {
        sender() ! GoAway
        log.debug("Surplus node {} registered, told to go away", host)
      } else {
        registeredHosts += host
        sender() ! Registered
        log.info("Node {} registered, total of {} unique nodes", host, registeredHosts.size)
      }
      val enoughNodes = registeredHosts.size >= expectedJoiningCount
      val enoughSeedNodesAndBroadcasters = seedNodes.size >= broadcasterCount + 1 // seed
      val hasAlreadyStarted = batchStartTime > 0
      val okToJoin = enoughNodes && enoughSeedNodesAndBroadcasters && !hasAlreadyStarted
      if (okToJoin) {
        availableHosts ++= registeredHosts
        nextBatch(InitialBatchSize)
        timers.startTimerWithFixedDelay(ProgressTick, ProgressTick, InitialProgressTimeout)
      }
    case NextBatch =>
      if (availableHosts.isEmpty && joinedHosts.size < expectedJoiningCount) {
        // everyone should be here, but they aren't
        // likely the results of small partitions (individual instances can get partitioned)
        // ask for backup
        availableHosts ++= registeredHosts.take(expectedJoiningCount - joinedHosts.size)
      }
      nextBatch(IncrementalBatchSize)
    case up: MemberUp =>
      up.member.address.host.foreach { host =>
        lastAddedMemberTime = System.currentTimeMillis()
        if (joiningHosts.contains(host)) {
          joinedHosts += host
          joiningHosts -= host

          if(joiningHosts.size - BatchJitter == 0) {
            val duration = FiniteDuration(System.currentTimeMillis() - batchStartTime, TimeUnit.MILLISECONDS)
            progressTimeout = duration * 2
            timers.cancel(ProgressTick)
            timers.startTimerWithFixedDelay(ProgressTick, ProgressTick, progressTimeout)
            log.info("Batch of size {} took {} seconds to complete",
              batchSize,
              duration.toSeconds
            )
            if(joinedHosts.size < expectedJoiningCount) {
              timers.startSingleTimer(NextBatch, NextBatch, BatchInterval)
            } else if (joinedHosts.size == expectedJoiningCount) {
              log.info("REACHED TARGET SIZE of {}!!!!", joinedHosts.size)
              log.info("Scheduling kill of 1% in 2 minutes")
              timers.startSingleTimer(KillOnePercent, KillOnePercent, 2.minutes)
            }
          }
          log.debug("Host {} joined, total of {} joined hosts and {} joining", host, joinedHosts.size, joiningHosts.size)
        } else {
          // seed (this node) or broadcasting nodes
          seedNodes += host
        }
      }
    case ProgressTick =>
      if (joinedHosts.size < expectedJoiningCount && (System.currentTimeMillis() - lastAddedMemberTime) > InitialProgressTimeout.toMillis) {
        log.info("Forcing progress after {} ms, removing {} timed out joiners", InitialProgressTimeout.toMillis, joiningHosts.size)
        joiningHosts = Set.empty
        self ! NextBatch
      }
    case KillOnePercent =>
      val onePercent = expectedMemberCount / 100
      val candidates = Cluster(context.system).state.members
        .filter(_.status == MemberStatus.Up)
        .filterNot(m => m.hasRole("seed") || m.hasRole("broadcaster"))
        .map(_.address)
      val victims = Random.shuffle(candidates).take(onePercent)
      log.info("Sending kill message to {} instances", onePercent)
      victims.foreach { victim =>
        context.actorSelection(RootActorPath(victim) / "user" / "listener") ! ActionListener.Kill

      }
    case StopAll =>
      (registeredHosts ++ joiningHosts ++ joinedHosts ++ availableHosts ++ seedNodes).foreach { host =>
        sendRequest(host, "leave")
      }
    case _: MemberEvent => // ignore
  }

  private def nextBatch(size: Int): Unit = {
    batchSize = size
    log.info("Allowing batch of {} nodes to join", size)
    batchStartTime = System.currentTimeMillis()
    val batch = availableHosts.take(size)
    availableHosts = availableHosts.diff(batch)
    joiningHosts ++= batch
    joiningHosts.foreach { host =>
      sendRequest(host, "ready")
    }
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
  final case class Register(host: String)
  sealed trait Registration
  final case object Registered extends Registration
  final case object GoAway extends Registration

  final case object NextBatch
  final case object ProgressTick

  final case object KillOnePercent

  final case object StopAll

  val InitialBatchSize = 1000
  val IncrementalBatchSize = 500
  val BatchJitter = 20
  val SpareHostsLimit = 50

  val BatchInterval = 15.seconds
  val ProgressCheckInterval = 15.seconds
  val InitialProgressTimeout = 1.minute

}