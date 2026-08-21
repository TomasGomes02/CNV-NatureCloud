#!/bin/bash
source config.sh
INSTANCE_DNS=$(cat instance.dns)
SSH_KEY="-o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH"

ssh $SSH_KEY ec2-user@$INSTANCE_DNS "sudo rpm --import https://yum.corretto.aws/corretto.key && sudo curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo && sudo yum install -y java-22-amazon-corretto-devel && mkdir -p /home/ec2-user/output"

scp $SSH_KEY $DIR/../lab-javassist/target/JavassistWrapper-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$INSTANCE_DNS:/home/ec2-user/Agent.jar
scp $SSH_KEY $DIR/../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$INSTANCE_DNS:/home/ec2-user/WebServer.jar

AGENT_FLAGS="-javaagent:/home/ec2-user/Agent.jar=ComplexityEstimator:pt.ulisboa.tecnico.cnv:output"
START_CMD="export AWS_DEFAULT_REGION=eu-west-2; java -Xmx500m $AGENT_FLAGS -cp /home/ec2-user/WebServer.jar pt.ulisboa.tecnico.cnv.webserver.WebServer > /home/ec2-user/server.log 2>&1 &"

cmd="echo -e \"#!/bin/bash\n$START_CMD\" | sudo tee /etc/rc.d/rc.local; sudo chmod +x /etc/rc.d/rc.local"
ssh $SSH_KEY ec2-user@$INSTANCE_DNS "$cmd"
