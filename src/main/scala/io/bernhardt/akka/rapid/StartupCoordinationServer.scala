package io.bernhardt.akka.rapid

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Timers}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

/**
 * Simplistic register to keep track of started JVMs so that we can bootstrap clustering at roughly the same time
 */
class StartupCoordinationServer[T](interface: String, expectedMemberCount: Int)(implicit system: ActorSystem) {
  import StartupCoordinator._

  implicit val askTimeout = Timeout(1.second)

  val coordinator = system.actorOf(Props(new StartupCoordinator(expectedMemberCount)), "coordinator")

  val route =
    concat(
      path("register" / ".*".r) { host =>
        post {
          coordinator ! Register(host)
          complete(OK)
        }
      },
      path("ready" / ".*".r) { host =>
        get {
          onComplete(coordinator ? IsReady(host)) {
            case Success(Ready) => complete(OK)
            case Success(NotReady) => complete(PreconditionFailed)
            case Failure(_) => complete(PreconditionFailed)
          }
        }
      }
    )

  Http().bindAndHandle(route, interface, 8080)

}

class StartupCoordinator(expectedMemberCount: Int) extends Actor with Timers with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[MemberEvent])

  import StartupCoordinator._
  var registeredHosts = Set.empty[String]
  var availableHosts = Set.empty[String]
  var joiningHosts = Set.empty[String]
  var joinedHosts = Set.empty[String]

  var batchStartTime = System.currentTimeMillis()
  var batchSize = 0
  var lastAddedMemberTime = System.currentTimeMillis()

  def receive = {
    case Register(host) =>
      log.info("Node {} registered, total of {} unique nodes", host, registeredHosts.size)
      registeredHosts += host
      if (registeredHosts.size == expectedMemberCount - 1) {
        availableHosts ++= registeredHosts
        nextBatch(InitialBatchSize)
        timers.startTimerWithFixedDelay(ProgressTick, ProgressTick, ProgressTimeout)
      }
    case IsReady(host) if joiningHosts(host) || joinedHosts(host) =>
      sender() ! Ready
    case IsReady(_) =>
      sender() ! NotReady
    case NextBatch =>
      nextBatch(IncrementalBatchSize)
    case up: MemberUp =>
      up.member.address.host.foreach { host =>
        lastAddedMemberTime = System.currentTimeMillis()
        if (joiningHosts.contains(host)) {
          joinedHosts += host
          joiningHosts -= host

          if(joiningHosts.isEmpty) {
            log.info("Batch of size {} took {} seconds to complete",
              batchSize,
              FiniteDuration(System.currentTimeMillis() - batchStartTime, TimeUnit.MILLISECONDS).toSeconds
            )
            if(joinedHosts.size < expectedMemberCount) {
              timers.startSingleTimer(NextBatch, NextBatch, BatchInterval)
            }
          }
          log.info("Host {} joined, total of {} joined hosts and {} joining", host, joinedHosts.size, joinedHosts.size)
        } else {
          log.warning("Host {} joined not part of joining hosts?", host)
        }
      }
    case ProgressTick =>
      if (joinedHosts.size < expectedMemberCount && (System.currentTimeMillis() - lastAddedMemberTime) > ProgressTimeout.toMillis) {
        log.info("Forcing progress after {} ms", ProgressTimeout.toMillis)
        self ! NextBatch
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
  }

}

object StartupCoordinator {
  final case class Register(host: String)
  final case class IsReady(host: String)
  sealed trait Readiness
  final case object Ready extends Readiness
  final case object NotReady extends Readiness

  final case object NextBatch
  final case object ProgressTick

  val InitialBatchSize = 10
  val IncrementalBatchSize = 10

  val BatchInterval = 15.seconds
  val ProgressCheckInterval = 15.seconds
  val ProgressTimeout = 2.minutes

}