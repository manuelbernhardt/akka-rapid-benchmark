#!/usr/bin/env bash
set -e

cd /opt/async-profiler
sudo su
./profiler.sh -d 500 -f /tmp/`hostname`.svg `cat /tmp/akka.pid`
chown ubuntu:ubuntu /tmp/`hostname`.svg