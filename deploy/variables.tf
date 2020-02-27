variable "platform" {
    default = "ubuntu"
    description = "The OS Platform"
}

variable "user" {
    default = {
        ubuntu  = "ubuntu"
    }
}

variable "ami" {
  description = "AWS AMI Id, if you change, make sure it is compatible with instance type, not all AMIs allow all instance types "

  default = {
    us-east-1-ubuntu      = "ami-046842448f9e74e7d",
    us-west-1-ubuntu      = "ami-0d58800f291760030",
    eu-west-1-ubuntu      = "ami-07042e91d04b1c30d",
    eu-central-1-ubuntu   = "ami-0718a1ae90971ce4d"
  }
}

variable "service_conf" {
    default = {
        ubuntu  = "debian_upstart.conf"
    }
}

variable "key_name" {
  description = "SSH key name in your AWS account for AWS instances."
}

variable "key_path" {
    description = "Path to the private key used to access the cloud servers"
}

variable "region" {
  default     = "us-east-1"
  description = "The region of AWS, for AMI lookups."
}

variable "availability_zone" {
  default = "us-east-1c"
  description = "The AZ to use inside fo the region"
}

variable "servers" {
    default     = "3"
    description = "The number of Akka nodes to launch"
}

variable "members" {
    default     = "3"
    description = "The number of Akka nodes the actor system expects to work with"
}

variable "elastic_cloudid" {
    default     = ""
    description = "Elastic cloud id"
}

variable "elastic_kibana" {
    default     = ""
    description = "Elastic Kibana host"
}

variable "elastic_cloudauth" {
    default     = ""
    description = "Elastic cloud auth"
}

variable "system_name" {
    default     = "akka-cluster"
    description = "The actor system name"
}

variable "instance_type" {
  default     = "t3.small"
  description = "AWS Instance type, if you change, make sure it is compatible with AMI, not all AMIs allow all instance types "
}

variable "aws_security_group" {
  description = "Name of the AWS Security Group"
}

variable "subnet_id_1" {
  description = "First subnet id"
}

variable "subnet_id_2" {
  description = "Second subnet id"
}

variable "subnet_id_3" {
  description = "Third subnet id"
}

variable "tag_name" {
    default     = "akka"
    description = "Name tag for the servers"
}
