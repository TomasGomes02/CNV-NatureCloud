#!/bin/bash
source config.sh

echo "--- STARTING FULL TEARDOWN ---"

echo "1. Hunting down and terminating ALL Master and Worker Instances..."
# We search AWS for ANY instance wearing our specific IAM Roles, making it impossible for them to hide!
ACTIVE_INSTANCES=$(aws ec2 describe-instances \
    --filters "Name=iam-instance-profile.arn,Values=*instance-profile/CNV-Master-Role,*instance-profile/CNV-Worker-Role" "Name=instance-state-name,Values=running,pending" \
    --query "Reservations[*].Instances[*].InstanceId" --output text)
    
if [ -n "$ACTIVE_INSTANCES" ]; then
    aws ec2 terminate-instances --instance-ids $ACTIVE_INSTANCES >/dev/null
    echo " -> Terminated instances: $ACTIVE_INSTANCES"
else
    echo " -> No Master or Worker instances found running."
fi
rm -f master.id

echo "2. Terminating temporary AMI Builder Instance (if it exists)..."
if [ -f instance.id ]; then
    BUILDER_ID=$(cat instance.id)
    aws ec2 terminate-instances --instance-ids $BUILDER_ID 2>/dev/null
    echo " -> Terminated Builder instance: $BUILDER_ID"
    rm -f instance.id
fi

# Give AWS a few seconds to begin shutting down the instances before we delete their underlying data
sleep 5 

echo "3. Deleting Golden AMI and associated EBS Snapshots..."
if [ -f image.id ]; then
    IMAGE_ID=$(cat image.id)
    
    # We must find the hidden Snapshot ID before deregistering the image
    SNAPSHOT_ID=$(aws ec2 describe-images --image-ids $IMAGE_ID \
        --query 'Images[*].BlockDeviceMappings[*].Ebs.SnapshotId' --output text 2>/dev/null)
    
    aws ec2 deregister-image --image-id $IMAGE_ID 2>/dev/null
    echo " -> Deregistered AMI: $IMAGE_ID"
    
    if [ -n "$SNAPSHOT_ID" ]; then
        # Wait a few seconds for the AMI lock to release before deleting the snapshot
        sleep 5 
        aws ec2 delete-snapshot --snapshot-id $SNAPSHOT_ID 2>/dev/null
        echo " -> Deleted underlying EBS Snapshot: $SNAPSHOT_ID"
    fi
    rm -f image.id
fi

echo "4. Extracting final metrics from DynamoDB to local CSV..."

# 4a. Temporarily ensure the table is in On-Demand billing mode to handle the read spike
aws dynamodb update-table --table-name CNVMetrics --billing-mode PAY_PER_REQUEST >/dev/null 2>&1 || true

# 4b. Scan the table safely using a small page-size so AWS doesn't throttle us!
aws dynamodb scan \
    --table-name CNVMetrics \
    --page-size 15 \
    --output json > temp_metrics.json

# 4c. Verify the scan actually worked before claiming success
if [ -s temp_metrics.json ]; then
    cat temp_metrics.json | jq -r '.Items[] | [.requestId.S, .requestType.S, .params.S, .complexity.N, .timestamp.N] | @csv' > ../local_metrics.csv

    # Check if metrics.csv has content
    if [ -s ../local_metrics.csv ]; then
        echo " -> Successfully safely paginated and saved metrics.csv to your local machine!"
    else
        echo " -> ERROR: temp_metrics.json was created, but metrics.csv is empty. Check jq parsing."
    fi
else
    echo " -> FATAL ERROR: AWS DynamoDB Scan failed or table is empty."
fi

# Clean up the temp file
rm -f temp_metrics.json

echo "6. Deleting DynamoDB Table..."
aws dynamodb delete-table --table-name CNVMetrics 2>/dev/null
echo " -> Deleted CNVMetrics table."

echo "7. Deleting Lambdas..."
./delete-lambdas.sh

echo "8. Cleaning up Master IAM Roles..."
# Detach policies and remove instance profiles so the role can be cleanly deleted
aws iam remove-role-from-instance-profile --instance-profile-name CNV-Master-Role --role-name CNV-Master-Role 2>/dev/null
aws iam delete-instance-profile --instance-profile-name CNV-Master-Role 2>/dev/null

aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AWSLambda_FullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess 2>/dev/null
aws iam detach-role-policy --role-name CNV-Master-Role --policy-arn arn:aws:iam::aws:policy/IAMFullAccess 2>/dev/null

aws iam delete-role --role-name CNV-Master-Role 2>/dev/null
echo " -> Deleted Master IAM Roles."

echo "9. Cleaning up Worker IAM Roles..."
aws iam remove-role-from-instance-profile --instance-profile-name CNV-Worker-Role --role-name CNV-Worker-Role 2>/dev/null
aws iam delete-instance-profile --instance-profile-name CNV-Worker-Role 2>/dev/null

aws iam detach-role-policy --role-name CNV-Worker-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess 2>/dev/null
aws iam delete-role --role-name CNV-Worker-Role 2>/dev/null
echo " -> Deleted Worker IAM Roles."

echo "--- TEARDOWN COMPLETE ---"
