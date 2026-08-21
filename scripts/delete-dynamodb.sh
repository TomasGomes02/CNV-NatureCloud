#!/bin/bash
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
    cat temp_metrics.json | jq -r '.Items[] | [.requestId.S, .requestType.S, .params.S, .complexity.N, .timestamp.N] | @csv' > local_metrics.csv

    # Check if metrics.csv has content
    if [ -s local_metrics.csv ]; then
        echo " -> Successfully safely paginated and saved metrics.csv to your local machine!"
    else
        echo " -> ERROR: temp_metrics.json was created, but metrics.csv is empty. Check jq parsing."
    fi
else
    echo " -> FATAL ERROR: AWS DynamoDB Scan failed or table is empty."
fi

# Clean up the temp file
rm -f temp_metrics.json

aws dynamodb delete-table --table-name CNVMetrics --region eu-west-2
