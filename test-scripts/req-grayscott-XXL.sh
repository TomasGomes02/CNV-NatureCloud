#!/bin/bash

ts=$(date +'%Y%m%d_%H%M%S')

echo "Sending XXL Gray-Scott request. This will take over 60 seconds..."

curl -s "http://ec2-13-40-104-195.eu-west-2.compute.amazonaws.com:80/grayscott?size=512&maxIterations=4000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "grayscott_XXL_${ts}.png"