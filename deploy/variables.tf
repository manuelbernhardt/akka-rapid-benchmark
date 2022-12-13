variable "project" {
  description = "The name of the project"
  default     = "akka-rapid-benchmark"
}

variable "environment" {
  description = "The deployment environment"
  default     = "production"
}

# Networking

variable "region" {
  default     = "us-east-1"
  description = "The region of AWS, for AMI lookups."
}

variable "availability_zones" {
  type        = list(any)
  default     = ["us-east-1a", "us-east-1b"]
  description = "The names of the availability zones to use"
}

variable "vpc_cidr" {
  description = "The CIDR block of the vpc"
}

variable "public_subnets_cidr" {
  type        = list(any)
  description = "The CIDR block for the public subnet"
}

variable "private_subnets_cidr" {
  type        = list(any)
  description = "The CIDR block for the private subnet"
}

# OS

variable "platform" {
    default = "ubuntu"
    description = "The OS Platform"
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

variable "user" {
    default = {
        ubuntu  = "ubuntu"
    }
}

variable "service_conf" {
    default = {
        ubuntu  = "debian_upstart.conf"
    }
}

# SSH

variable "key_name" {
  description = "SSH key name in your AWS account for AWS instances."
}

variable "key_path" {
    description = "Path to the private key used to access the cloud servers"
}

# Elastic Search

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

variable "apm_server_url" {
  default = ""
  description = "Elastic APM server url"
}

variable "apm_token" {
  default = ""
  description = "Elastic APM token"
}

# Experiment setup

variable "node_instance_type" {
  default     = "t3.small"
  description = "AWS Instance type of a normal node."
}

variable "seed_node_instance_type" {
  default     = "c5.2xlarge"
  description = "AWS instance type of the seed node which is going to establish a lot more connections."
}

variable "broadcast_node_instance_type" {
  default     = "c5.2xlarge"
  description = "AWS instance type of the broadcast nodes which will establish many connections and handle more traffic."
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

variable "broadcasters" {
  default     = "0"
  description = "The number of Akka nodes that serve as Rapid broadcasters"
}

variable "system_name" {
    default     = "akka-cluster"
    description = "The actor system name"
}