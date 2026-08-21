#!/bin/bash
source config.sh

# 1. Kill the Master Load Balancer
if [ -f "master.id" ]; then
    MASTER_ID=$(cat master.id)
    echo "Terminating Master Load Balancer VM ($MASTER_ID)..."
    aws ec2 terminate-instances --instance-ids $MASTER_ID >/dev/null
    rm master.id
else
    echo "master.id not found! Skipping Master LB termination."
fi

# 2. Hunt down and kill any orphaned Worker VMs spawned by the AutoScaler
echo "Hunting for any active Worker VMs..."
WORKER_IDS=$(aws ec2 describe-instances \
    --filters "Name=iam-instance-profile.arn,Values=*instance-profile/CNV-Worker-Role" "Name=instance-state-name,Values=running,pending" \
    --query "Reservations[*].Instances[*].InstanceId" --output text)

if [ -n "$WORKER_IDS" ]; then
    echo "Found orphaned workers: $WORKER_IDS. Terminating..."
    aws ec2 terminate-instances --instance-ids $WORKER_IDS >/dev/null
else
    echo "No orphaned workers found."
fi

# 3. Clean up the Master IAM Role
echo "Cleaning up Master IAM Role..."
aws iam remove-role-from-instance-profile --instance-profile-name CNV-Master-Role --role-name CNV-Master-Role 2>/dev/null
aws iam delete-instance-profile --instance-profile-name CNV-Master-Role 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AWSLambda_FullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/IAMFullAccess 2>/dev/null
aws iam delete-role --role-name CNV-Master-Role 2>/dev/null

echo "Master LB and all associated workers have been terminated."