#!/usr/bin/env bash
set -e

echo "Installing monit for broadcaster"
sudo systemctl stop monit.service
sudo killall -9 java
sudo mv /home/ubuntu/akka-cluster-broadcaster /etc/monit/conf.d/akka-cluster
sudo systemctl start monit.service
sudo systemctl enable monit.service