package io.bernhardt.akka.rapid


import akka.Done
import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent._
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import akka.pattern.ask

object Main extends App {

  val logger = LoggerFactory.getLogger("main")

  val SystemName = "ClusterSystem"

  val WaitForMembersTimeout = 3.hours

  def seedNodeConfig(host: String) = ConfigFactory.parseString(
    s"""
       |akka.cluster.seed-nodes = [ "akka://$SystemName@$host:25520" ]
       |""".stripMargin)

  def remotingPortConfig(port: String) = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = $port
       |""".stripMargin
  )

  val hostname: String = sys.env("HOSTNAME")

  val port = if (args.length > 0) args(0) else "25520"

  val seedNode = sys.env.get("SEED_NODE").filterNot(_.trim.isEmpty)

  val isSeedNode = seedNode.contains("SEED")

  val isRunningOnAWS = !sys.env.get("AWS_REGION").forall(_.trim.isEmpty)

  val expectedMemberCount = sys.env.get("EXPECT_MEMBERS").filterNot(_.trim.isEmpty).map(_.toInt).getOrElse(0)

  def start(): Unit = {
    if (!isSeedNode) {
      logger.info("Waiting for all {} JVMs to be ready", expectedMemberCount)
    }

    Await.ready(waitForJVMs()(ExecutionContext.global), WaitForMembersTimeout).onComplete {
      case Success(_) => // okay
      case Failure(t) =>
        logger.error("Waiting for {} JVMs to start timed out after {} min", expectedMemberCount, WaitForMembersTimeout.toMinutes, t)
        System.exit(-1)
    }(ExecutionContext.global)

//    if (!isSeedNode) {
//      logger.info("==== {} JVMs are running, starting clustering when the next minute starts", expectedMemberCount)
//      waitUntilNextMinute()
//    }

    startClusterSystem()
  }

  def waitForJVMs()(implicit ec: ExecutionContext): Future[Done] = {
    seedNode.map {
      case "SEED" =>
        // don't wait
        Future.successful(Done)
      case seedHostname =>
        val config = ConfigFactory.parseString(
          """
            |akka {
            |  loggers = ["akka.event.slf4j.Slf4jLogger"]
            |  loglevel = "DEBUG"
            |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
            |
            |  http {
            |    server {
            |      max-connections = 10240
            |      idle-timeout = 10 s
            |    }
            |  }
            |
            |  coordinated-shutdown.exit-jvm = off
            |
            |}""".stripMargin).withFallback(ConfigFactory.defaultReference())
        val akkaHttpSystem = ActorSystem("http", config)
        akkaHttpSystem.registerOnTermination {
          logger.info("Shutting down http ActorSystem")
        }
        val coordinationClient = akkaHttpSystem.actorOf(StartupCoordinationClient.props(hostname, seedHostname))
        val waitForOthers = coordinationClient.ask(StartupCoordinationClient.Register)(WaitForMembersTimeout).mapTo[Done]
        waitForOthers.flatMap { _ =>
          logger.info("{} JVMs running", expectedMemberCount)
          akkaHttpSystem.terminate()
        }
        .map { _ => Done }
    } getOrElse {
      Future.successful(Done)
    }
  }

  def startClusterSystem(): Unit = {
    val system: Option[ActorSystem] = seedNode.map {
      case "SEED" =>
        logger.info("Starting as seed node, remoting port {}", port)
        val system = ActorSystem(SystemName, seedNodeConfig(hostname).withFallback(remotingPortConfig(port).withFallback(ConfigFactory.load())))
        logger.info("Seed node starting to listen for JVM registrations")
        new StartupCoordinationServer(hostname, expectedMemberCount)(system)
        Some(system)
      case "TEMPLATE" =>
        // we're the template node and should not be running
        None
      case host =>
        logger.info("Starting with seed node configured at {}, remoting port {}", host, port)
        Some(ActorSystem(SystemName, seedNodeConfig(host).withFallback(remotingPortConfig(port).withFallback(ConfigFactory.load()))))
    } getOrElse {
      logger.info("Starting local system")
      Some(ActorSystem(SystemName, remotingPortConfig(port).withFallback(ConfigFactory.load())))
    }

    system.foreach { s =>
      s.registerOnTermination {
        logger.warn("Shutting down cluster ActorSystem")
      }
      val cluster = Cluster(s)
      s.actorOf(MembershipRecorder.props(expectedMemberCount), "recorder")
      s.actorOf(ActionListener.props(), "blocker")
      logger.info("==== Akka Node {} started", cluster.selfAddress.toString)
    }
  }

  start()

}