#!/bin/bash
source config.sh

WORKER_AMI=$(cat image.id)

echo "1. Setting up IAM Role for the Master Load Balancer..."
aws iam create-role --role-name CNV-Master-Role --assume-role-policy-document '{"Version": "2012-10-17","Statement": [{ "Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}' 2>/dev/null || echo "Role already exists."

aws iam attach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess
aws iam attach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AWSLambda_FullAccess
aws iam attach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess
aws iam attach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/IAMFullAccess

aws iam create-instance-profile --instance-profile-name CNV-Master-Role 2>/dev/null || echo "Instance profile already exists."
aws iam add-role-to-instance-profile --instance-profile-name CNV-Master-Role --role-name CNV-Master-Role 2>/dev/null

echo "Waiting 10 seconds for AWS IAM to register the new role..."
sleep 10

echo "2. Booting Blank Master Load Balancer..."
# Notice we are using the blank Amazon Linux AMI here, NOT $WORKER_AMI!
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids $AWS_SECURITY_GROUP \
	--iam-instance-profile Name=CNV-Master-Role \
	--metadata-options "HttpTokens=optional" | jq -r ".Instances[0].InstanceId" > master.id

echo "Waiting for Master instance to initialize..."
aws ec2 wait instance-running --instance-ids $(cat master.id)
MASTER_IP=$(aws ec2 describe-instances --instance-ids $(cat master.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName")

echo "3. Waiting for SSH to become available on $MASTER_IP..."
while ! nc -z $MASTER_IP 22; do
	sleep 0.5
done
sleep 5 # Give SSH daemon a few extra seconds to fully accept connections

echo "4. Installing Java and uploading Load Balancer..."
SSH_KEY="-o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH"

# Install Java on the blank image
ssh $SSH_KEY ec2-user@$MASTER_IP "sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y"

# Upload the freshly compiled Load Balancer JAR from your laptop
# MAKE SURE THIS PATH MATCHES YOUR LAPTOP FOLDER STRUCTURE!
scp $SSH_KEY ../loadBalancer/target/LoadBalancerWrapper-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$MASTER_IP:/home/ec2-user/LoadBalancer.jar
scp $SSH_KEY ../local_metrics.csv ec2-user@$MASTER_IP:/home/ec2-user/local_metrics.csv

echo "5. Starting Load Balancer service..."
# We run it using 'sudo' so it can bind to port 80.
START_CMD="export WORKER_AMI=$WORKER_AMI; export AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION; export AWS_SECURITY_GROUP=$AWS_SECURITY_GROUP; export AWS_KEYPAIR_NAME=$AWS_KEYPAIR_NAME; sudo -E java -Xmx500m -jar /home/ec2-user/LoadBalancer.jar > /home/ec2-user/lb.log 2>&1 &"
ssh $SSH_KEY ec2-user@$MASTER_IP "$START_CMD"

echo "====================================================="
echo "✅ Load Balancer is live at: http://$MASTER_IP"
echo "To view logs, SSH in and run: cat /home/ec2-user/lb.log"
echo "====================================================="