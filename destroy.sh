#!/bin/bash

cd deploy
export TF_VAR_servers=10
export TF_VAR_members=10
export TF_VAR_region=us-east-1
export TF_VAR_key_name=akka
export TF_VAR_key_path=/home/manu/.ssh/akka.pem
export TF_VAR_aws_security_group=sg-1234
export TF_VAR_subnet_id_1=subnet-1
export TF_VAR_subnet_id_2=subnet-2
export TF_VAR_subnet_id_3=subnet-3
export TF_VAR_elastic_cloudid=abc
export TF_VAR_elastic_kibana=abc
export TF_VAR_elastic_cloudauth=abc

terraform destroy -refresh=true -parallelism=500 -auto-approve
