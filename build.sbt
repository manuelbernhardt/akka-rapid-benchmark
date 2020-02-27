name := "akka-rapid-benchmark"

version := "1.0"

scalaVersion := "2.13.1"

lazy val akkaVersion = "2.6.4-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http"   % "10.1.10",
  "com.github.seratch" %% "awscala-ec2" % "0.8.+",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.5.2",
  "ch.qos.logback.contrib" % "logback-jackson" % "0.1.5",
  "ch.qos.logback.contrib" % "logback-json-classic" % "0.1.5",
  "com.papertrailapp" % "logback-syslog4j" % "1.0.0",
  "org.codehaus.janino" % "janino" % "3.1.0",
  "com.vrg" % "rapid" %  "1.0-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)

excludeDependencies ++= Seq(
  ExclusionRule("org.slf4j", "slf4j-log4j12")
)

resolvers += Resolver.mavenLocal

resolvers += Resolver.file("ivy-local", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns)

enablePlugins(JavaAppPackaging)

mainClass in Compile := Some("io.bernhardt.akka.rapid.Main")

maintainer := "manuel@bernhardt.io" // keep native packager from complaining

bashScriptExtraDefines ++= Seq(
  "export HOSTNAME=`hostname -f`",
  "export SEED_NODE=$(/opt/ec2-metadata | grep user-data | awk '{print $2}' | awk -F'|' '{print $1}')",
  "export EXPECT_MEMBERS=$(/opt/ec2-metadata | grep user-data | awk '{print $2}' | awk -F'|' '{print $2}')",
  "export PAPERTRAIL_HOST=",
  "export PAPERTRAIL_PORT=",
  "export SYSTEM_NAME=ClusterSystem",
  "export AWS_ACCESS_KEY_ID=",
  "export AWS_ACCESS_KEY_SECRET=",
  "export AWS_REGION="
)