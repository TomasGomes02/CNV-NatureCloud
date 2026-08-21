#!/bin/bash

source config.sh

# Create load balancer and configure health check.
aws elb create-load-balancer \
	--load-balancer-name CNV-LoadBalancer \
	--listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
  	--availability-zones eu-west-2a

aws elb configure-health-check \
  	--load-balancer-name CNV-LoadBalancer \
  	--health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=10,Timeout=5

# Create Launch Template
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat image.id)\",
		\"InstanceType\": \"t3.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"Monitoring\": {\"Enabled\": true},
		\"IamInstanceProfile\": {\"Name\": \"CNV-Worker-Role\"}
}"

# Create auto scaling group with launch template.
aws autoscaling create-auto-scaling-group \
  	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--launch-template LaunchTemplateName=CNV-LaunchTemplate,Version=1 \
  	--load-balancer-names CNV-LoadBalancer \
  	--availability-zones eu-west-2a \
  	--health-check-type ELB \
  	--health-check-grace-period 60 \
  	--min-size 1 \
  	--max-size 3 \
  	--desired-capacity 1

SCALE_UP_POLICY_ARN=$(aws autoscaling put-scaling-policy \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-name ScaleUpPolicy \
    --scaling-adjustment 1 \
    --adjustment-type ChangeInCapacity \
    --cooldown 60 \
    --query 'PolicyARN' --output text)

echo "Scale Up Policy created: $SCALE_UP_POLICY_ARN"

SCALE_DOWN_POLICY_ARN=$(aws autoscaling put-scaling-policy \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-name ScaleDownPolicy \
    --scaling-adjustment -1 \
    --adjustment-type ChangeInCapacity \
    --cooldown 60 \
    --query 'PolicyARN' --output text)

echo "Scale Down Policy created: $SCALE_DOWN_POLICY_ARN"

aws cloudwatch put-metric-alarm \
    --alarm-name HighCPUAlarm \
    --metric-name CPUUtilization \
    --namespace AWS/EC2 \
    --statistic Average \
    --period 60 \
    --threshold 70 \
    --comparison-operator GreaterThanThreshold \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --evaluation-periods 1 \
    --alarm-actions $SCALE_UP_POLICY_ARN

aws cloudwatch put-metric-alarm \
    --alarm-name LowCPUAlarm \
    --metric-name CPUUtilization \
    --namespace AWS/EC2 \
    --statistic Average \
    --period 60 \
    --threshold 30 \
    --comparison-operator LessThanThreshold \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --evaluation-periods 1 \
    --alarm-actions $SCALE_DOWN_POLICY_ARN
