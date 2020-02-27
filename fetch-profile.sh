#!/bin/bash

ssh -oStrictHostKeyChecking=no ubuntu@$1 './profile.sh'
scp -oStrictHostKeyChecking=no ubuntu@$1:/tmp/*.svg .