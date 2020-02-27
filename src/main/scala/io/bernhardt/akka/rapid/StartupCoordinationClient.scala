package io.bernhardt.akka.rapid

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NonFatal

class StartupCoordinationClient(selfHostName: String, seedHostname: String) extends Actor with ActorLogging {

  import StartupCoordinationClient._
  import akka.pattern.{pipe, retry}
  import context.dispatcher

  implicit val mat = context.system

  implicit def scheduler = context.system.scheduler

  val http = Http()(context.system)

  var parent: ActorRef = Actor.noSender

  def receive: Receive = {
    case Register =>
      if (parent == Actor.noSender) {
        parent = sender()
      }
      log.info("Registering at seed node {}", seedHostname)
      val seedRegistration = retry(() => registration, 50, 10.seconds).map {
        _ =>
          log.info("Successfully registered at seed node {}", seedHostname)
          Registered
      } recover {
        case NonFatal(exception) => log.error(exception, "Failed to register! Retrying...")
          Register
      }

      seedRegistration pipeTo self

    case Registered =>
      context.become(waiting)
      self ! WaitForMembers
  }

  def waiting: Receive = {
    case WaitForMembers =>
      log.info("Starting to wait for other members at an interval of {} seconds, maximum timeout of {} minutes", WaitForMembersDelay.toSeconds, Main.WaitForMembersTimeout.toMinutes)
      val attempts = (Main.WaitForMembersTimeout.toSeconds / WaitForMembersDelay.toSeconds).toInt
      retry(() => checkReadiness, attempts, WaitForMembersDelay).recover {
        case NonFatal(t) =>
          log.error(t, "Waiting for members failed, giving up")
      } pipeTo self
    case Done =>
      parent ! Done
  }

  def registration = http.singleRequest(HttpRequest(
    method = HttpMethods.POST,
    uri = s"http://$seedHostname:8080/register/$selfHostName")
  ).flatMap {
    case response if response.status == StatusCodes.OK =>
      response.discardEntityBytes().future()
    case response =>
      response.discardEntityBytes().future().flatMap { _ =>
        Future.failed(new IllegalStateException(s"Status code ${response.status.defaultMessage()}"))
      }
  }

  def checkReadiness = http.singleRequest(HttpRequest(
    method = HttpMethods.GET,
    uri = s"http://$seedHostname:8080/ready/$selfHostName")
  ).flatMap {
    case response if response.status == StatusCodes.OK =>
      response.discardEntityBytes().future()
    case response =>
      response.discardEntityBytes().future().flatMap { _ =>
        Future.failed(new IllegalStateException("System not ready"))
      }
  }


}

object StartupCoordinationClient {

  val WaitForMembersDelay = 5.seconds

  case object Register

  protected case object Registered

  protected case object WaitForMembers

  def props(selfHostName: String, seedHostname: String) = Props(new StartupCoordinationClient(selfHostName, seedHostname))
}