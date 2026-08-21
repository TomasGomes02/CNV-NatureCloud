#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source $SCRIPT_DIR/config.sh

if [ -z "$1" ]; then
    echo "Usage: ./invoke-lambdas.sh <function-name> '<json-payload>'"
    echo "Example: ./invoke-lambdas.sh fractals-lambda '{ \"w\": \"400\", \"h\": \"400\", \"iterations\": \"100\" }'"
    exit 1
fi

#./invoke-lambdas.sh fractals-lambda '{ "w": "400", "h": "400", "iterations": "100" }'

FUNCTION_NAME=$1
PAYLOAD=$2

echo "Invoking $FUNCTION_NAME..."

aws lambda invoke \
    --function-name "$FUNCTION_NAME" \
    out.json \
    --cli-binary-format raw-in-base64-out \
    --payload "$PAYLOAD" \
    --log-type Tail \
    --query 'LogResult' \
    --output text | base64 -d

echo -e "\n\nLambda Response Output:"
cat out.json
echo -e "\n"
