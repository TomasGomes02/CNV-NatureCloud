#!/bin/bash

# Ensure we are in the scripts directory
cd "$(dirname "$0")"

echo "--- STARTING FULL DEPLOYMENT ---"

echo "Step 1: Creating DynamoDB..."
./create-dynamodb.sh

# 2. Create the Golden AMI
echo "Step 2: Creating VM Image (this will take a few minutes)..."
./create-image.sh

# 3. Create the Lambda functions
echo "Step 3: Deploying Lambda Functions..."
./create-lambdas.sh

# 4. Launch the Load Balancer and Auto Scaler
echo "Step 4: Launching elastic infrastructure..."
./launch-master-lb.sh

echo "--- DEPLOYMENT COMPLETE ---"