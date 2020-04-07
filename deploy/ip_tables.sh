#!/usr/bin/env bash
set -e

sudo iptables -I INPUT -s 0/0 -p tcp --dport 2020 -j ACCEPT
sudo iptables -I INPUT -s 0/0 -p tcp --dport 2030 -j ACCEPT
sudo iptables -I INPUT -s 0/0 -p tcp --dport 2552 -j ACCEPT
#sudo iptables -I INPUT -s 0/0 -p tcp --dport 35520 -j ACCEPT

if [ -d /etc/sysconfig ]; then
  sudo iptables-save | sudo tee /etc/sysconfig/iptables
else
  sudo iptables-save | sudo tee /etc/iptables.rules
fi
