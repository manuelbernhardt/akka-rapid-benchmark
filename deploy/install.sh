#!/usr/bin/env bash
set -e

echo "Extending file limits"
sudo sh -c "echo '*               hard    nofile            131072' >> /etc/security/limits.conf"
sudo sh -c "echo '*               soft    nofile            131072' >> /etc/security/limits.conf"
sudo sh -c "echo 'root            hard    nofile            131072' >> /etc/security/limits.conf"
sudo sh -c "echo 'root            soft    nofile            131072' >> /etc/security/limits.conf"
sudo sh -c "echo 'session required pam_limits.so' >> /etc/pam.d/common-session"
sudo sh -c "echo 'fs.file-max = 131072' >> /etc/sysctl.conf"

# Because Ubuntu
sudo sh -c "echo 'DefaultLimitNOFILE=131072' >> /etc/systemd/system.conf"
sudo sh -c "echo 'DefaultLimitNOFILE=131072' >> /etc/systemd/user.conf"

# ARP table cache defaults are too low for this scale when deploying in the same AZ
sudo sh -c "echo 'net.ipv4.neigh.default.gc_interval=3600' >> /etc/sysctl.conf"
sudo sh -c "echo 'net.ipv4.neigh.default.gc_stale_time=3600' >> /etc/sysctl.conf"
sudo sh -c "echo 'net.ipv4.neigh.default.gc_thresh1=16384' >> /etc/sysctl.conf"
sudo sh -c "echo 'net.ipv4.neigh.default.gc_thresh2=28672' >> /etc/sysctl.conf"
sudo sh -c "echo 'net.ipv4.neigh.default.gc_thresh3=32768' >> /etc/sysctl.conf"

# More sockets - 2480 outgoing sockets / second, useful for broadcasting to 10k nodes
sudo sh -c "echo 'net.ipv4.ip_local_port_range=\"3000 65000\"' >> /etc/sysctl.conf"
sudo sh -c "echo 'net.ipv4.tcp_fin_timeout=25' >> /etc/sysctl.conf"

# Support a higher connection rate (default 128)
sudo sh -c "echo 'sysctl net.core.somaxconn=1024' >> /etc/sysctl.conf"

# Increase conntrack table size
sudo sh -c "echo 'net.ipv4.netfilter.ip_conntrack_max = 32768' >> /etc/sysctl.conf"

echo "Installing dependencies..."
sudo apt-get update
sudo apt-get install -y openjdk-11-jdk
sudo apt-get install -y openjdk-11-dbg
sudo apt-get install -y unzip
sudo apt-get install -y chrony
sudo apt-get install -y wget
sudo apt-get install -y monit
sudo apt-get install -y jq

curl -L -O https://artifacts.elastic.co/downloads/beats/filebeat/filebeat-7.6.0-amd64.deb
sudo dpkg -i filebeat-7.6.0-amd64.deb

cd /opt
sudo wget http://s3.amazonaws.com/ec2metadata/ec2-metadata
sudo chmod +x ec2-metadata

sudo mkdir async-profiler
cd async-profiler
sudo wget https://github.com/jvm-profiling-tools/async-profiler/releases/download/v1.7/async-profiler-1.7-linux-x64.tar.gz
sudo tar -xzf async-profiler-1.7-linux-x64.tar.gz

echo "Configuring chrony to use AWS servers"
echo 'server 169.254.169.123 prefer iburst minpoll 4 maxpoll 4' > /tmp/chrony.conf
cat  /etc/chrony/chrony.conf >> /tmp/chrony.conf
sudo cp /tmp/chrony.conf /etc/chrony/chrony.conf
sudo systemctl stop chrony.service
sudo systemctl start chrony.service
sudo systemctl enable chrony.service

echo "Configuring filebeat"
sudo cp /tmp/filebeat.yml /etc/filebeat
sudo systemctl stop filebeat.service
sudo systemctl start filebeat.service
sudo systemctl enable filebeat.service

echo "Installing akka..."
SYSTEM_NAME=$(cat /tmp/system-name | tr -d '\n')
AWS_ACCESS_KEY_ID=$(cat /tmp/aws-access-key-id | tr -d '\n')
AWS_ACCESS_KEY_SECRET=$(cat /tmp/aws-access-key-secret | tr -d '\n')
AWS_REGION=$(cat /tmp/aws-region | tr -d '\n')

sudo cp /tmp/akka-rapid-benchmark-1.0.zip /opt
cd /opt
sudo unzip akka-rapid-benchmark-1.0.zip
sudo mv akka-rapid-benchmark-1.0 akka
sudo sed -i 's/  exec "$@"/  exec "$@" \& echo "$!" > \/tmp\/akka.pid/g' /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s/export PAPERTRAIL_HOST=.*/export PAPERTRAIL_HOST=${PAPERTRAIL_HOST}/g" /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s/export PAPERTRAIL_PORT=.*/export PAPERTRAIL_PORT=${PAPERTRAIL_PORT}/g" /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s/export SYSTEM_NAME=.*/export SYSTEM_NAME=${SYSTEM_NAME}/g" /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s/export AWS_ACCESS_KEY_ID=.*/export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}/g" /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s~export AWS_ACCESS_KEY_SECRET=.*~export AWS_ACCESS_KEY_SECRET=${AWS_ACCESS_KEY_SECRET}~g" /opt/akka/bin/akka-rapid-benchmark
sudo sed -i "s/export AWS_REGION=.*/export AWS_REGION=${AWS_REGION}/g" /opt/akka/bin/akka-rapid-benchmark
sudo chmod +x /opt/akka/bin/akka-rapid-benchmark

echo "Installing monit"
sudo mv /home/ubuntu/akka-cluster /etc/monit/conf.d
sudo sed -i 's/  set daemon 120/  set daemon 15/g' /etc/monit/monitrc
sudo systemctl restart monit.service
sudo systemctl enable monit.service