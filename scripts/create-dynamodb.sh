#!/bin/bash
source config.sh


aws dynamodb create-table \
    --table-name CNVMetrics \
    --attribute-definitions \
        AttributeName=requestId,AttributeType=S \
    --key-schema \
        AttributeName=requestId,KeyType=HASH \
    --provisioned-throughput \
        ReadCapacityUnits=5,WriteCapacityUnits=5 \
    --region eu-west-2

#echo "Waiting 15 seconds for table to become active..."
#sleep 15
#
#echo "2. Populating DynamoDB from local_metrics.csv..."
#count=0
#
## Read the CSV file line by line
#while IFS='|' read -r signature complexity type params; do
#    # Skip empty lines
#    if [ -z "$signature" ]; then continue; fi
#
#    ts=$(date +%s%3N) # Current timestamp
#
#    # Push to DynamoDB
#    aws dynamodb put-item \
#        --table-name CNVMetrics \
#        --item '{"requestId": {"S": "'"$signature"'"}, "requestType": {"S": "'"$type"'"}, "params": {"S": "'"$params"'"}, "complexity": {"N": "'"$complexity"'"}, "timestamp": {"N": "'"$ts"'"}}' \
#        --region $AWS_DEFAULT_REGION > /dev/null
#
#    count=$((count+1))
#    # Print progress every 50 items
#    if (( count % 50 == 0 )); then echo " -> Uploaded $count items..."; fi
#done < ../local_metrics.csv # <-- MAKE SURE THIS PATH MATCHES YOUR CSV LOCATION!
#
#echo "✅ DynamoDB successfully populated with $count historical metrics."
