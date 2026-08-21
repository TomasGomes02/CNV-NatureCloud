#!/bin/bash
source config.sh

echo "1. Setting up IAM Role for Worker Nodes..."
aws iam create-role \
    --role-name CNV-Worker-Role \
    --assume-role-policy-document '{"Version": "2012-10-17","Statement": [{ "Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}' 2>/dev/null || echo "Worker Role already exists."

# Attach permissions (Workers only need DynamoDB to push metrics)
aws iam attach-role-policy --role-name CNV-Worker-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess

aws iam create-instance-profile --instance-profile-name CNV-Worker-Role 2>/dev/null || echo "Worker Instance profile already exists."
aws iam add-role-to-instance-profile --instance-profile-name CNV-Worker-Role --role-name CNV-Worker-Role 2>/dev/null

echo "Waiting 10 seconds for AWS IAM changes to propagate..."
sleep 10

echo "2. Booting Base VM for Golden AMI..."
aws ec2 run-instances \
    --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --instance-type t3.micro \
    --key-name $AWS_KEYPAIR_NAME \
    --security-group-ids $AWS_SECURITY_GROUP \
    --iam-instance-profile Name=CNV-Worker-Role \
    --monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > instance.id

echo "Waiting for instance to initialize..."
aws ec2 wait instance-running --instance-ids $(cat instance.id)
aws ec2 describe-instances --instance-ids $(cat instance.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > instance.dns

echo "Waiting for SSH to become available..."
while ! nc -z $(cat instance.dns) 22; do
    sleep 0.5
done
echo "Base VM is ready!"