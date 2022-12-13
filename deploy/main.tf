provider "aws" {
  region = var.region
}

module "networking" {
    source = "./modules/networking"

    project              = var.project
    environment          = var.environment
    region               = var.region
    availability_zones   = var.availability_zones
    vpc_cidr             = var.vpc_cidr
    public_subnets_cidr  = var.public_subnets_cidr
    private_subnets_cidr = var.private_subnets_cidr
}

# User & SSH

resource "aws_iam_user" "akka-user" {
    name = "akka-user"
}

resource "aws_iam_access_key" "akka" {
    user = aws_iam_user.akka-user.name
}

resource "aws_iam_user_policy" "akka_ro" {
    name = "akka-policy"
    user = aws_iam_user.akka-user.name

    policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Action": [
        "ec2:Describe*",
        "ec2:StopInstances"
      ],
      "Effect": "Allow",
      "Resource": "*"
    }
  ]
}
EOF
}

# Instance template
# We create one instance, configure it, and turn it into an AMI template to use as a way to bootstrap the fleet
# Any other approach would be too time-consuming as we bootstrap a lot of servers

resource "aws_instance" "akka-template-instance" {
    ami = lookup(var.ami, "${var.region}-${var.platform}")
    instance_type = var.node_instance_type
    key_name = var.key_name
    vpc_security_group_ids = [ module.networking.default_sg_id ]

    user_data = base64encode("TEMPLATE")

    tags = {
        Name = "${var.project}-template"
        Environment = var.environment
    }

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/files/profile.sh"
        destination = "/home/ubuntu/profile.sh"
    }
    provisioner "file" {
        source      = "${path.module}/files/akka-cluster"
        destination = "/home/ubuntu/akka-cluster"
    }
    provisioner "file" {
        source      = "${path.module}/files/akka-cluster-seed"
        destination = "/home/ubuntu/akka-cluster-seed"
    }
    provisioner "file" {
        source      = "${path.module}/files/akka-cluster-broadcaster"
        destination = "/home/ubuntu/akka-cluster-broadcaster"
    }
    provisioner "file" {
        content     = templatefile("${path.module}/files/filebeat.yml.tpl", {
            cloud_id = var.elastic_cloudid,
            cloud_auth = var.elastic_cloudauth,
            kibana_host = var.elastic_kibana
        })
        destination = "/tmp/filebeat.yml"
    }
    provisioner "file" {
        source      = "${path.module}/../target/universal/akka-rapid-benchmark-1.0.zip"
        destination = "/tmp/akka-rapid-benchmark-1.0.zip"
    }

    provisioner "remote-exec" {
        inline = [
            "echo ${var.elastic_cloudid} > /tmp/elastic-cloudid",
            "echo ${var.elastic_cloudauth} > /tmp/elastic-cloudauth",
            "echo ${var.elastic_kibana} > /tmp/elastic-kibana",
            "echo ${var.system_name} > /tmp/system-name",
            "echo ${aws_iam_access_key.akka.id} > /tmp/aws-access-key-id",
            "echo ${aws_iam_access_key.akka.secret} > /tmp/aws-access-key-secret",
            "echo ${var.region} > /tmp/aws-region",
            "chmod +x /home/ubuntu/profile.sh"
        ]
    }

    provisioner "remote-exec" {
        scripts = [
            "${path.module}/scripts/install.sh",
            "${path.module}/scripts/ip_tables.sh",
        ]
    }

}

resource "aws_ami_from_instance" "akka-template-ami" {
  name               = "akka-template-ami-${aws_instance.akka-template-instance.id}"
  source_instance_id = aws_instance.akka-template-instance.id
}

# The seed node

resource "aws_instance" "akka_seed_node" {
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.seed_node_instance_type
    vpc_security_group_ids = [ module.networking.default_sg_id ]
    key_name = var.key_name

    availability_zone = var.availability_zone

    tags = {
        Name = "${var.project}-seed"
        Environment = var.environment
    }


    user_data = base64encode("SEED|${var.members}|false|${var.broadcasters}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/files/install-monit-seed.sh"
        destination = "/tmp/install-monit-seed.sh"
    }

    provisioner "remote-exec" {
        inline = [
            "chmod +x /tmp/install-monit-seed.sh",
            "/tmp/install-monit-seed.sh 'SEED'"
        ]
    }
}

# The broadcast nodes

resource "aws_instance" "akka-broadcasters" {
    count = var.broadcasters
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.broadcast_node_instance_type
    vpc_security_group_ids = [ module.networking.default_sg_id ]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = module.networking.private_subnets_id[0]

    tags = {
        Name = "${var.project}-broadcaster-${count.index}"
        Environment = var.environment
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_ip}|${var.members}|true|${var.broadcasters}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/files/install-monit-broadcaster.sh"
        destination = "/tmp/install-monit-broadcaster.sh"
    }

    provisioner "remote-exec" {
        inline = [
            "chmod +x /tmp/install-monit-broadcaster.sh",
            "/tmp/install-monit-broadcaster.sh 'SEED'"
        ]
    }

}

# The regular nodes, split in 3 subnets (as otherwise we exhaust the subnet IP space...)

resource "aws_instance" "akka-1" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.node_instance_type
    vpc_security_group_ids = [ module.networking.default_sg_id ]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = module.networking.private_subnets_id[0]

    tags = {
        Name = "${var.project}-${count.index}"
        Environment = var.environment
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_ip}|${var.members}|false|${var.broadcasters}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
    depends_on = [ aws_instance.akka-broadcasters ]
}
resource "aws_instance" "akka-2" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.node_instance_type
    vpc_security_group_ids = [ module.networking.default_sg_id ]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = module.networking.private_subnets_id[1]

    tags = {
        Name = "${var.project}-${count.index}"
        Environment = var.environment
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_ip}|${var.members}|false|${var.broadcasters}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
    depends_on = [ aws_instance.akka-broadcasters ]
}
resource "aws_instance" "akka-3" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.node_instance_type
    vpc_security_group_ids = [ module.networking.default_sg_id ]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = module.networking.private_subnets_id[2]

    tags = {
        Name = "${var.project}-${count.index}"
        Environment = var.environment
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_ip}|${var.members}|false|${var.broadcasters}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
    depends_on = [ aws_instance.akka-broadcasters ]
}