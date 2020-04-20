#!/bin/bash

rm -rf target
sbt universal:packageBin
cd target/universal
unzip -o *.zip
cd ../..

AMOUNT=$1
START=1
END=$(($AMOUNT-1))
SEED_NODE=SEED HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms4096m -J-Xmx4096m -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints 2552 &
echo "Started seed node, PID $!"
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms8192m -J-Xmx8192m -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2553 &
echo "Started broadcaster node, PID $!"
IS_BROADCASTER=true HOSTNAME=localhost EXPECT_MEMBERS=$1 target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms8192m -J-Xmx8192m -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Dakka.cluster.rapid.act-as-consistent-hash-broadcaster=true 2554 &
echo "Started broadcaster node, PID $!"

echo "Sleeping"
sleep 15

i=$START
while [[ $i -le $END ]]
do
  HOSTNAME=localhost EXPECT_MEMBERS=$AMOUNT target/universal/akka-rapid-benchmark-1.0/bin/akka-rapid-benchmark -J-Xms64m -J-Xmx80m "$((2554 + $i))" &
  echo "Started node $i with PID $!"



  # Join in batches of 20, with a 10 second delay in between
  #if ! ((i % 20)); then
  # sleep 30
  #fi

  # Join in batches of 100, with a 10 second delay in between
#  if ! ((i % 100)); then
#   sleep 10
#  fi
  


  ((i = i + 1))
done
echo "Done launching"
