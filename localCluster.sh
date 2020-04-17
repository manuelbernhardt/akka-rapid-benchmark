#!/bin/bash

sbt universal:packageBin
cd target/universal
unzip -o *.zip
cd ../..

AMOUNT=$1
START=1
END=$(($AMOUNT-1))
SEED_NODE=SEED HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms64m -J-Xmx1024m 2552 &
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms128m -J-Xmx1024m -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2553 &
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms128m -J-Xmx1024m -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2554 &
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms128m -J-Xmx1024m -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2555 &
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms128m -J-Xmx1024m -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2556 &
echo "Sleeping"
sleep 15
HOSTNAME=localhost EXPECT_MEMBERS=$AMOUNT target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms64m -J-Xmx80m "$((2557))" &
echo "Sleeping more -PID is $!"
sleep 15

i=$START
while [[ $i -le $END ]]
do
  echo "Starting $i"
  HOSTNAME=localhost EXPECT_MEMBERS=$AMOUNT target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms64m -J-Xmx80m "$((2557 + $i))" &

  # Join in batches of 20, with a 10 second delay in between
  # if ! ((i % 20)); then
  #  sleep 10
  # fi

  # Join in batches of 10, with a 10 second delay in between
  if ! ((i % 10)); then
   sleep 10
  fi
  


  ((i = i + 1))
done
echo "Done launching"
