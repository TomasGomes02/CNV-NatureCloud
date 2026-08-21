# CNV - Nature@Cloud

This project contains the following sub-projects:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads
5. `lab-javassist` - the custom Javassist bytecode instrumentation agent used to calculate dynamic request complexity.

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

---

## Architecture Overview

The production deployment of **Nature@Cloud** uses an automated, horizontally scalable architecture across AWS to balance processing workloads dynamically.

The live architecture operates as an asynchronous feedback loop across three primary layers:

* **The Entry & Elastic Routing Layer (AWS Classic ELB):** Acts as the single public gateway. It routes all user traffic, verifies instance health, and maps internal port configurations to incoming user queries.
* **The Dynamic Compute Array (AWS EC2 + Auto Scaling Group):** A horizontally scalable cluster of processing nodes running a specialized distribution of **Amazon Linux 2023**. Each node embeds the `webserver` processing logic alongside a **Javassist bytecode-manipulation agent** that records instruction-level execution metrics natively.
* **The Persistent Memory Layer (AWS DynamoDB):** A NoSQL metrics engine that stores operation parameters and complexity of executed workloads, closing the feedback loop between worker processing complexity and predictive load balancing.

---

## AWS Infrastructure Configurations

The elastic platform configurations deployed by the infrastructure scripts are detailed below:

### 1. Metrics Storage System (DynamoDB)

* **Table Name:** `CNVMetrics`
* **Primary Key Schema:** `requestId` (Type: `S` / String) acting as a unique `HASH` key
* **Provisioned Throughput:** 5 Read Capacity Units (RCUs) and 5 Write Capacity Units (WCUs)
* **Deployment Target:** Deployed in the London region (`eu-west-2`)

### 2. Elastic Load Balancer (ELB)

The public gateway utilizes an AWS Classic Load Balancer to intercept and balance web traffic across worker nodes:

* **Load Balancer Name:** `CNV-LoadBalancer`
* **Availability Zones:** Initialized inside `eu-west-2a`
* **Listener Configuration:** Maps external public HTTP traffic on **Port 80** to internal instance workloads listening on HTTP **Port 8000**
* **Health Check Parameters:**
* **Target Endpoint:** `HTTP:8000/test`
* **Interval:** 30 seconds between checks
* **Timeout:** 5 seconds before a check fails
* **Healthy Threshold:** 10 consecutive successful checks required to mark an instance healthy
* **Unhealthy Threshold:** 2 consecutive failed checks required to mark an instance unhealthy



### 3. Auto Scaling Group (ASG) & CloudWatch Triggers

Horizontal cluster scaling is governed by an Auto Scaling Group mapped to a custom Golden Image Launch Template:

* **Auto Scaling Group Name:** `CNV-AutoScalingGroup`
* **Capacity Boundaries:**
* **Minimum Size:** 1 instance
* **Maximum Size:** 3 instances
* **Desired Capacity:** 1 instance


* **Health Check Specification:** Evaluated directly via the `ELB` with a grace period of 60 seconds
* **Scaling Policies & Metrics Alarms:**
* **Scale Up Triggers:** Governed by `HighCPUAlarm`. Monitors average `CPUUtilization` in the ASG over a 60-second evaluation period. If utilization exceeds **70%**, a `ScaleUpPolicy` executes a capacity adjustment of **+1 instance** (with a 60-second cooldown).
* **Scale Down Triggers:** Governed by `LowCPUAlarm`. Monitors average `CPUUtilization` in the ASG over a 60-second evaluation period. If utilization drops below **30%**, a `ScaleDownPolicy` executes a capacity adjustment of **-1 instance** (with a 60-second cooldown).



### 4. Image Template (AMI & EC2 Workers)

The computational instances spin up using a customized, reproducible Amazon Machine Image (AMI):

* **Instance Type:** `t3.micro` (Burst-capable dual-core compute architecture)
* **Base OS Environment:** Amazon Linux 2023
* **Software Stack:** Amazon Corretto Java 11 Development Kit (`java-22-amazon-corretto-devel`)
* **Monitoring:** Detailed CloudWatch metrics are explicitly enabled (`Monitoring=Enabled`)
* **Boot-Time Automation:** The server registers itself directly into `/etc/rc.d/rc.local` during configuration. When the Auto Scaler provisions a new node, it executes this initialization string at system startup:
```bash
export AWS_DEFAULT_REGION=eu-west-2
java -javaagent:/home/ec2-user/Agent.jar=ComplexityEstimator:pt.ulisboa.tecnico.cnv:output -cp /home/ec2-user/WebServer.jar pt.ulisboa.tecnico.cnv.webserver.WebServer > /home/ec2-user/server.log 2>&1 &

```



---

## How to Build Everything

1. Make sure your `JAVA_HOME` environment variable is set to a Java 11+ distribution.
2. Run the following Maven command from the root directory to clean, compile, and build all 5 sub-modules simultaneously:
```bash
mvn clean package

```



---

## Local Execution and Testing

To start the web server locally and attach the Javassist agent so it can intercept incoming workloads and calculate instruction complexity, run the following command from the root directory:

```bash
java -javaagent:lab-javassist/target/JavassistWrapper-1.0.0-SNAPSHOT-jar-with-dependencies.jar=ComplexityEstimator:pt.ulisboa.tecnico.cnv:output -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.webserver.WebServer

```

---

## Production Cloud Deployment

The entire system architecture, including databases, custom AMIs, health-checks, load balancers, and metrics triggers, is fully automated.

### Prerequisites

Before triggering a remote deployment, verify that your local environment has access to the following pre-configured AWS assets inside your `scripts/config.sh` file:

* An active AWS Security Group allowing ingress on port 22 (SSH) and port 8000 (Web Server).
* An operational AWS EC2 Keypair configuration for worker authentication.
* An IAM Instance Profile named `CNV-Worker-Role` allowing worker nodes to securely read/write metrics streams.

### Automated Deployment Setup

To provision the entire production ecosystem on AWS, change into the automation directory and execute the primary deployment controller:

```bash
cd scripts/
source config.sh
./deploy-all.sh

```

### Automation Script Pipeline Actions

When you run `./deploy-all.sh`, the orchestrator automatically executes these consecutive routines:

1. **`create-dynamodb.sh`:** Seeds your AWS environment with the `CNVMetrics` NoSQL database table.
2. **`create-image.sh`:** Spins up a temporary canary instance, executes `install-vm.sh` to install Java 11 and download your compiled code JAR files, executes `test-vm.sh` to confirm auto-booting capabilities over port 8000, packages the instance state into a permanent `CNV-Image` AMI, and cleans up the staging server.
3. **`launch-deployment-template.sh`:** Links the newly created AMI to a baseline Launch Template, generates the frontend `CNV-LoadBalancer`, builds the target `CNV-AutoScalingGroup`, and establishes the CloudWatch alarms to scale the cluster dynamically between 1 and 3 nodes.
