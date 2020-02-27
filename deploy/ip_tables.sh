#!/usr/bin/env bash
set -e

sudo iptables -I INPUT -s 0/0 -p tcp --dport 8080 -j ACCEPT
sudo iptables -I INPUT -s 0/0 -p tcp --dport 25520 -j ACCEPT
sudo iptables -I INPUT -s 0/0 -p tcp --dport 35520 -j ACCEPT

if [ -d /etc/sysconfig ]; then
  sudo iptables-save | sudo tee /etc/sysconfig/iptables
else
  sudo iptables-save | sudo tee /etc/iptables.rules
fi
