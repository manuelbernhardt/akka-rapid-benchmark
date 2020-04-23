package io.bernhardt.akka.rapid


import akka.actor.ActorSystem
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

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

  def largeNodeConfig = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.advanced.buffer-pool-size = 512
       |akka.remote.artery.advanced.large-buffer-pool-size = 1
       |akka.remote.artery.advanced.outbound-large-message-queue-size = 1
       |akka.remote.artery.advanced.outbound-message-queue-size = 1536
       |akka.remote.artery.advanced.tcp.connection-timeout = 10 seconds
       |""".stripMargin
  )

  val hostname: String = sys.env("HOSTNAME")

  val port = if (args.length > 0) args(0) else "2552"

  val seedNode = sys.env.get("SEED_NODE").filterNot(_.trim.isEmpty)

  val isSeedNode = seedNode.contains("SEED")

  val isBroadcaster = sys.env.get("IS_BROADCASTER").filterNot(_.trim.isEmpty).contains("true")

  val isStartedAsBroadcaster = sys.props.get("akka.cluster.rapid.act-as-consistent-hash-broadcaster").contains("true")

  val isRunningOnAWS = !sys.env.get("AWS_REGION").forall(_.trim.isEmpty)

  val expectedMemberCount = sys.env.get("EXPECT_MEMBERS").filterNot(_.trim.isEmpty).map(_.toInt).getOrElse(0)

  val expectedBroadcastersCount = sys.env.get("EXPECT_BROADCASTERS").filterNot(_.trim.isEmpty).map(_.toInt).getOrElse(0)

  def start(): Unit = {
    if (!isSeedNode) {
      logger.info("Waiting for all {} JVMs to be ready", expectedMemberCount)
    }
    startClusterSystem()
  }

  def startClusterSystem(): Unit = {
    val system: Option[ActorSystem] = seedNode.map {
      case "SEED" =>
        import java.time.Duration
        logger.info("Starting as seed node, remoting port {}", port)
        val config = seedNodeConfig(hostname)
            .withFallback(remotingPortConfig(port)
            .withFallback(largeNodeConfig)
            .withFallback(ConfigFactory.load()))
            .withValue("akka.cluster.roles", ConfigValueFactory.fromIterable(List("seed").asJava))
            // batch all the joiner messages together in one message
            .withValue("akka.cluster.rapid.batching-window", ConfigValueFactory.fromAnyRef(Duration.ofSeconds(2)))
        val system = ActorSystem(SystemName, config)
        logger.info("Seed node starting to listen for JVM registrations with {} broadcasters", expectedBroadcastersCount)
        new StartupCoordinationServer(hostname, expectedMemberCount, expectedBroadcastersCount)(system)
        startActors(system)
        Some(system)
      case "TEMPLATE" =>
        // we're the template node and should not be running
        None
      case host if isStartedAsBroadcaster =>
        logger.info("Starting broadcasting node with seed node configured at {}, remoting port {}", host, port)
        val config = remotingPortConfig(port)
          .withFallback(largeNodeConfig)
          .withFallback(ConfigFactory.load())
          .withValue("akka.cluster.roles", ConfigValueFactory.fromIterable(List("broadcaster").asJava))
        val system = ActorSystem(SystemName, config)
        new StartupClient(hostname, host, expectedMemberCount, isBroadcaster = true)(system)
        Some(system)
      case _ if isBroadcaster && !isStartedAsBroadcaster =>
        // this is a broadcaster process starting for the first time, before it gets the proper startup params
        // discard it
        logger.info("Not ready broadcaster started, waiting to be stopped")
        None
      case host =>
        logger.info("Starting with seed node {}, {} broadcasters, remoting port {}", host, expectedBroadcastersCount, port)
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