# Akka Cluster Provisioning

Terraform script for bootstrapping an Akka Cluster on AWS as EC2 instances.

Make sure you have configured enough subnets in the AZ you want to use.

You can run this with a script such as the following one:

```shell script
#!/bin/bash

export TF_VAR_servers=1000
export TF_VAR_region=us-east-1
export TF_VAR_availability_zone=us-east-1c
export TF_VAR_key_name=akka
export TF_VAR_key_path=/home/ubuntu/.ssh/akka.pem
export TF_VAR_aws_security_group=sg-25880654
export TF_VAR_elastic_cloudid=foo:bar
export TF_VAR_elastic_kibana=foo.us-east-1.aws.found.io:9243
export TF_VAR_elastic_cloudauth=elastic:bar
terraform apply -parallelism=100 -auto-approve
```
