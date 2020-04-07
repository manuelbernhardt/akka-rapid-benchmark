package io.bernhardt.akka.rapid

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, Props, Timers}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NonFatal

class StartupClient(selfHostName: String, seedHostName: String, expectedMemberCount: Int)(implicit system: ActorSystem) {

  val client = system.actorOf(StartupCoordinationClient.props(selfHostName, seedHostName, expectedMemberCount), "startup-client")

  client ! StartupCoordinationClient.Register

  val route = concat(
    path("ready") {
      post {
        client ! StartupCoordinationClient.Ready
        complete(OK)
      }
    },
    path("leave") {
      post {
        shutdownMachine()
        complete(OK)
      }
    }
  )

  Http().bindAndHandle(route, selfHostName, 2030)

}

class StartupCoordinationClient(selfHostName: String, seedHostname: String, expectedMemberCount: Int) extends Actor with ActorLogging with Timers {

  import StartupCoordinationClient._
  import akka.pattern.{pipe, retry}
  import context.dispatcher

  implicit val mat = context.system

  implicit def scheduler = context.system.scheduler

  val http = Http()(context.system)

  def receive: Receive = {
    case Register =>
      log.debug("Registering at seed node {}", seedHostname)
      val seedRegistration = retry(() => registration, 50, 10.seconds).map {
        _ =>
          log.debug("Successfully registered at seed node {}", seedHostname)
          Registered
      } recover {
        case NonFatal(exception) => log.error(exception, "Failed to register! Retrying...")
          Register
      }

      seedRegistration pipeTo self

    case Registered =>
      context.become(waiting)
  }

  def waiting: Receive = {
    case Ready =>
      val cluster = Cluster(context.system)
      cluster.join(Address(cluster.selfAddress.protocol, cluster.selfAddress.system, seedHostname, 2552))
      context.actorOf(MembershipRecorder.props(expectedMemberCount), "recorder")
      context.actorOf(ActionListener.props(disableSafetyStop = false), "listener")
      log.info("==== Akka Node {} started", cluster.selfAddress.toString)

  }

  def registration = http.singleRequest(HttpRequest(
    method = HttpMethods.POST,
    uri = s"http://$seedHostname:2020/register/$selfHostName")
  ).flatMap {
    case response if response.status == StatusCodes.OK =>
      response.discardEntityBytes().future()
    case response if response.status == StatusCodes.EnhanceYourCalm =>
      import scala.sys.process._
      response.discardEntityBytes().future()
      log.info("We're told to leave")
      shutdownMachine()
      Future.failed(new IllegalStateException())
    case response =>
      response.discardEntityBytes().future().flatMap { _ =>
        Future.failed(new IllegalStateException(s"Status code ${response.status.defaultMessage()}"))
      }
  }

}

object StartupCoordinationClient {

  case object Register
  case object Ready

  protected case object Registered

  def props(selfHostName: String, seedHostname: String, expectedMemberCount: Int) = Props(new StartupCoordinationClient(selfHostName, seedHostname, expectedMemberCount))
}