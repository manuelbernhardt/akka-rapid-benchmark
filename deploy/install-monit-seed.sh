#!/usr/bin/env bash
set -e

echo "Installing monit for seed"
sudo systemctl stop monit.service
sudo killall -9 java
sudo mv /home/ubuntu/akka-cluster-seed /etc/monit/conf.d/akka-cluster
sudo systemctl start monit.service
sudo systemctl enable monit.service