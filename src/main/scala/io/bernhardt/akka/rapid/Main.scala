package io.bernhardt.akka.rapid


import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object Main extends App {

  val logger = LoggerFactory.getLogger("main")

  val SystemName = "ClusterSystem"

  val WaitForMembersTimeout = 15.minutes

  def seedNodeConfig(host: String) = ConfigFactory.parseString(
    s"""
       |akka.cluster.seed-nodes = [ "akka://$SystemName@$host:2552" ]
       |""".stripMargin)

  def remotingPortConfig(port: String) = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = $port
       |""".stripMargin
  )

  val hostname: String = sys.env("HOSTNAME")

  val port = if (args.length > 0) args(0) else "2552"

  val seedNode = sys.env.get("SEED_NODE").filterNot(_.trim.isEmpty)

  val isSeedNode = seedNode.contains("SEED")

  val broadcasters = sys.env.get("BROADCASTERS").filterNot(_.trim.isEmpty).map(_.split(",")).getOrElse(Array.empty)

  val isBroadcaster = sys.props.get("akka.cluster.rapid.act-as-consistent-hash-broadcaster").contains("true")

  val isRunningOnAWS = !sys.env.get("AWS_REGION").forall(_.trim.isEmpty)

  val expectedMemberCount = sys.env.get("EXPECT_MEMBERS").filterNot(_.trim.isEmpty).map(_.toInt).getOrElse(0)

  def start(): Unit = {
    if (!isSeedNode) {
      logger.info("Waiting for all {} JVMs to be ready", expectedMemberCount)
    }
    startClusterSystem()
  }


  def startClusterSystem(): Unit = {
    val system: Option[ActorSystem] = seedNode.map {
      case "SEED" =>
        logger.info("Starting as seed node, remoting port {}", port)
        val system = ActorSystem(SystemName, seedNodeConfig(hostname).withFallback(remotingPortConfig(port).withFallback(ConfigFactory.load())))
        logger.info("Seed node starting to listen for JVM registrations")
        new StartupCoordinationServer(hostname, expectedMemberCount, broadcasters.size)(system)
        startActors(system)
        Some(system)
      case "TEMPLATE" =>
        // we're the template node and should not be running
        None
      case host if isBroadcaster =>
        logger.info("Starting broadcasting node with seed node configured at {}, remoting port {}", host, port)
        val system = ActorSystem(SystemName, seedNodeConfig(host).withFallback(remotingPortConfig(port).withFallback(ConfigFactory.load())))
        startActors(system)
        Some(system)

      case host =>
        logger.info("Starting with seed node {}, {} broadcasters, remoting port {}", host, broadcasters.size, port)
        val config = remotingPortConfig(port).withFallback(ConfigFactory.load())
        val system = ActorSystem(SystemName, config)
        new StartupClient(hostname, host, expectedMemberCount)(system)
        Some(system)
    } getOrElse {
      logger.info("Starting local system")
      val system = ActorSystem(SystemName, seedNodeConfig("localhost").withFallback(remotingPortConfig(port).withFallback(ConfigFactory.load())))
      startActors(system)
      Some(system)
    }

    system.foreach { s =>
      s.registerOnTermination {
        logger.warn("Shutting down cluster ActorSystem")
      }
    }
  }

  def startActors(system: ActorSystem): Unit = {
    system.actorOf(MembershipRecorder.props(expectedMemberCount), "recorder")
    system.actorOf(ActionListener.props(disableSafetyStop = true), "listener")
  }

  start()

}