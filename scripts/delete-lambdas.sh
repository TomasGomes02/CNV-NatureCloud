#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source $SCRIPT_DIR/config.sh

echo "Deleting Lambda functions..."
aws lambda delete-function --function-name fractals-lambda
aws lambda delete-function --function-name grayscott-lambda
aws lambda delete-function --function-name dna-lambda

echo "Detaching IAM policies..."
aws iam detach-role-policy \
	--role-name lambda-role \
	--policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

echo "Deleting IAM Role..."
aws iam delete-role --role-name lambda-role

echo "Cleanup complete."
