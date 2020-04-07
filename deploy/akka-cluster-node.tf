provider "aws" {
  region = var.region
}

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

resource "aws_instance" "akka-template-instance" {
    ami = lookup(var.ami, "${var.region}-${var.platform}")
    instance_type = var.instance_type
    key_name = var.key_name
    vpc_security_group_ids = [
        var.aws_security_group]

    user_data = base64encode("TEMPLATE")

    tags = {
        Name = "${var.tag_name}-template"
    }



    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/profile.sh"
        destination = "/home/ubuntu/profile.sh"
    }
    provisioner "file" {
        source      = "${path.module}/akka-cluster"
        destination = "/home/ubuntu/akka-cluster"
    }
    provisioner "file" {
        content     = templatefile("${path.module}/akka-cluster-seed.tpl", {
            service_name = "akka-rapid-seed",
            apm_server_url = var.apm_server_url,
            apm_token = var.apm_token
        })
        destination = "/home/ubuntu/akka-cluster-seed"
    }
    provisioner "file" {
        content     = templatefile("${path.module}/akka-cluster-broadcaster.tpl", {
            service_name = "akka-rapid-broadcaster",
            apm_server_url = var.apm_server_url,
            apm_token = var.apm_token
        })
        destination = "/home/ubuntu/akka-cluster-broadcaster"
    }
    provisioner "file" {
        content     = templatefile("${path.module}/filebeat.yml.tpl", {
            cloud_id = var.elastic_cloudid,
            cloud_auth = var.elastic_cloudauth,
            kibana_host = var.elastic_kibana
        })
        destination = "/tmp/filebeat.yml"
    }
    provisioner "file" {
        source      = "${path.module}/elastic-apm-agent-1.15.0.jar"
        destination = "/tmp/elastic-apm-agent-1.15.0.jar"
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
            "${path.module}/install.sh",
            "${path.module}/ip_tables.sh",
        ]
    }

}

resource "aws_ami_from_instance" "akka-template-ami" {
  name               = "akka-template-ami-${aws_instance.akka-template-instance.id}"
  source_instance_id = aws_instance.akka-template-instance.id
}

resource "aws_placement_group" "akka-cluster" {
  name     = "akka-cluster"
  strategy = "cluster"
}

resource "aws_instance" "akka_seed_node" {
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = "c5.2xlarge"
    vpc_security_group_ids = [var.aws_security_group]
    key_name = var.key_name

    availability_zone = var.availability_zone

    tags = {
        Name = "${var.tag_name}-seed"
    }

    user_data = base64encode("SEED|${var.members}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/install-monit-seed.sh"
        destination = "/tmp/install-monit-seed.sh"
    }

    provisioner "remote-exec" {
        inline = [
            "chmod +x /tmp/install-monit-seed.sh",
            "/tmp/install-monit-seed.sh 'SEED'"
        ]
    }

}

resource "aws_instance" "akka-broadcasters" {
    count = var.broadcasters
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = "c5.2xlarge"
    vpc_security_group_ids = [var.aws_security_group]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = var.subnet_id_1

    tags = {
        Name = "${var.tag_name}-broadcaster-${count.index}"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}|")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }

    provisioner "file" {
        source      = "${path.module}/install-monit-broadcaster.sh"
        destination = "/tmp/install-monit-broadcaster.sh"
    }

    provisioner "remote-exec" {
        inline = [
            "chmod +x /tmp/install-monit-broadcaster.sh",
            "/tmp/install-monit-broadcaster.sh 'SEED'"
        ]
    }

}

resource "aws_instance" "akka-1" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.instance_type
    vpc_security_group_ids = [var.aws_security_group]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = var.subnet_id_1

    tags = {
        Name = "${var.tag_name}-${count.index}"
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}|${join(",", aws_instance.akka-broadcasters.*.private_dns)}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
}
resource "aws_instance" "akka-2" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.instance_type
    vpc_security_group_ids = [var.aws_security_group]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = var.subnet_id_2

    tags = {
        Name = "${var.tag_name}-${count.index}"
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}|${join(",", aws_instance.akka-broadcasters.*.private_dns)}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
}
resource "aws_instance" "akka-3" {
    count = (var.servers - var.broadcasters - 1) / 3
    ami = aws_ami_from_instance.akka-template-ami.id
    instance_type = var.instance_type
    vpc_security_group_ids = [var.aws_security_group]
    key_name = var.key_name
    availability_zone = var.availability_zone
    subnet_id = var.subnet_id_3

    tags = {
        Name = "${var.tag_name}-${count.index}"
        SkipState = "true"
    }

    user_data = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}|${join(",", aws_instance.akka-broadcasters.*.private_dns)}")

    connection {
        host        = self.public_ip
        user        = lookup(var.user, var.platform)
        private_key = file(var.key_path)
    }
}

// fleets don't seem to launch in any reasonable time-frame

//resource "aws_launch_template" "akka" {
//    name = "akka"
//    image_id = aws_ami_from_instance.akka-template-ami.id
//    instance_type = var.instance_type
//    vpc_security_group_ids = [var.aws_security_group]
//    key_name = var.key_name
//    user_data = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}")
//
//    placement {
//        availability_zone = aws_instance.akka_seed_node.availability_zone
//    }
//}
//
//resource "aws_ec2_fleet" "akka-cluster" {
//
//    type = "maintain"
//
//    launch_template_config {
//        launch_template_specification {
//            launch_template_id = aws_launch_template.akka.id
//            version = aws_launch_template.akka.latest_version
//        }
//    }
//
//    target_capacity_specification {
//        default_target_capacity_type = "on-demand"
//        total_target_capacity = var.servers - 1
//    }
//}


// turns out that autoscaling group bear a lot of CloudWatch costs

//resource "aws_launch_configuration" "akka_launch" {
//    name            = "akka-cluster"
//    image_id        = aws_ami_from_instance.akka-template-ami.id
//    instance_type   = var.instance_type
//    security_groups = [var.aws_security_group]
//    key_name        = var.key_name
//    user_data       = base64encode("${aws_instance.akka_seed_node.private_dns}|${var.members}")
//
//    lifecycle {
//        create_before_destroy = true
//    }
//}

//resource "aws_autoscaling_policy" "akka" {
//    name                   = "akka-cluster-scaling"
//    scaling_adjustment     = 500
//    adjustment_type        = "ExactCapacity"
//    cooldown               = 30
//    autoscaling_group_name = aws_autoscaling_group.akka-cluster.name
//}

//resource "aws_autoscaling_group" "akka-cluster" {
//    name                 = "akka-cluster"
//    launch_configuration = aws_launch_configuration.akka_launch.id
//    min_size             = 1
//    max_size             = 10050
//    desired_capacity     = var.servers - 1 // seed is already running
//    availability_zones   = [ aws_instance.akka_seed_node.availability_zone ]
//    //placement_group      = aws_placement_group.akka-cluster.id
//
//    lifecycle {
//        create_before_destroy = true
//    }
//}

