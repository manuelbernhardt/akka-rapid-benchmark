# Akka Rapid Benchmark

This benchmark environment allows to deploy Akka Clusters of arbitrary size.
It has been used successfully in order to bootstrap a 10,000 node cluster as [described in this article](https://manuel.bernhardt.io/2020/04/29/10000-node-cluster-with-akka-and-rapid/).

It was developed primarily developed for use with the [Rapid membership protocol](https://www.usenix.org/conference/atc18/presentation/suresh) integration of Akka Cluster, but can also be used to bootstrap a regular Akka Cluster.

## Infrastructure setup

This project uses Terraform in order to deploy the infrastructure. See `deploy/akka-cluster` for details.

### AWS EC2 limits

Depending on the size of the cluster you want to launch, request the appropriate limit increases. For example, for 10k:

- Network interfaces: 10000
- Running On-Demand All Standard (A, C, D, H, I, M, R, T, Z) instances: 40000

### AWS API throttling limits

In order to bootstrap things faster, those would be nice to have (not sure if AWS will grant them to you though).

See [the documentation](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/throttling.html)

- `RunInstances` Request Token bucket size: 1000, refill rate 100
- `DescribeInstances` Request Token bucket size 1000, refill rate 100


### Subnet / VPC setup

Make sure to create 3 subnets that are in the same region and copy their IDs for later.

Make sure that nothing prevents the subnets from communicating with each other, i.e. check the security group settings and other networking rules.

To the very least all 3 subnets need to be able to talk to each other via the ports configured in `deploy/ip_tables.sh`.

### Custom Terraform AWS provider

In order to speed up concurrency when creating the infrastructure via terraform, you can use this hack:

```shell script
git clone git@github.com:manuelbernhardt/terraform-provider-aws.git
cd terraform-provider-aws
git checkout concurrency-hack
make tools
make build
```

- run `terraform init` from the `deploy` directory of this directory to create the fitting directory structure
- copy the resulting artifact from this custom build into the `plugins` subdirectory that works with your architecture, e.g. `deploy/.terraform/plugins/linux_amd64`. 

## Prioject setup instructions

### Building Rapid

```shell script
git clone git@github.com:manuelbernhardt/rapid.git
cd rapid
git checkout ch-broadcaster
mvn install
```

### Building Akka

```shell script
git clone git@github.com:manuelbernhardt/akka.git
cd akka
git checkout rapid
sbt -Dakka.build.useLocalMavenResolver=true -Dakka.build.scalaVersion=2.13.1
publishLocal
```

### Rebuilding Akka after a change

In order to have it tagged properly, you need to update the tag

```shell script
git tag -d v2.6.4-SNAPSHOT
git add .
git commit -m "Change description"
git tag -a v2.6.4-SNAPSHOT -m "Rapid"
sbt -Dakka.build.useLocalMavenResolver=true -Dakka.build.scalaVersion=2.13.1
publishLocal
```

### Generate an SSH key for the EC2 instances

Generate a key without passphrase, don't overwrite your default ssh key:

```
ssh-keygen -t rsa -b 2048 -v
Enter file in which to save the key: /tmp/akka
Enter passphrase (empty for no passphrase): Enter
Enter same passphrase again: Enter
Your identification has been saved in /tmp/akka.
Your public key has been saved in /tmp/akka.pub.
The key fingerprint is: ...
```

Convert it to PEM:

```shell script
openssl rsa -in /tmp/akka -outform pem > /home/user/akka.pem
```

Add it to the SSH agent, useful to ssh into the instances:

```shell script
ssh-add /home/user/akka.pem
```

### Preparing the deployment script

Copy the `deploy.sh.template` script to `deploy.sh` script and fill in the necessary values.

In order to get logging support, you'll need an [elastic.co](elastic.co) deployment.

## Running an experiment

- edit `StartupCoordinator.scala` if necessary and adjust the experimental setup parameters:

```scala
val InitialBatchSize = 500
val IncrementalBatchSize = 500
val SpareHostsLimit = 1500

val InitialBatchInterval = 25.seconds
val InitialProgressTimeout = 45.seconds
```

- run the experiment

```shell script
sh deploy.sh 100 0
```

This will launch a 100 node cluster with 0 broadcasters

Since the EC2 instances will be divided in groups of 3, you need to make sure that `num instances - num broadcasters - 1` is divisible by 3.

E.g.

```shell script
sh deploy.sh 10000 102
```

For a 10000 node cluster with 102 broadcasters

## Terminating instances

**DO NOT FORGET TO DO THIS** or else you'll pay for it (quite literally, with your money)

### Using the Instance Terminator

You need to have the aws cli tools installed and all the necessary environment variables set for this to work

```shell script
sbt
runMain io.bernhardt.akka.rapid.InstanceTerminator
```

### Stopping instances from the seed node

You can ssh to the seed node and create the `/tmp/akka-stop-all` file in order to quickly instruct all registered instances to shut down.

Get the hostname of the seed node using:

```shell script
sbt
runMain io.bernhardt.akka.rapid.SeedNodeAddress
```

Then run:

```shell script
ssh ubuntu@ec2-...
echo "Stop" > /tmp/akka-stop-all
```

Note that **this will only stop the instances**, it won't terminate them, don't forget to run the terminator later on.

## Plotting results

See the vega visualization in `plotting/vega.txt` in order to create a Vega visualzation in Kibana.

## Running locally

- comment out the `bashExtraDefines` in `build.sbt`
- enable the console appender / disable the file appender in `src/main/resources/logback.xml`
- run `sh localCluster.sh 50` to bootstrap a 50 nodes cluster on your machine. Make sure you have a strong machine

For reference, a 16 core AMD Threadripper with 64 GB of RAM can run clusters up to 200 nodes before it starts bursting into flames.