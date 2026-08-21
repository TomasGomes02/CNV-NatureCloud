package pt.ulisboa.tecnico.cnv.loadbalancer;

import pt.ulisboa.tecnico.cnv.loadbalancer.utils.VmState;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AutoScaler {

    // --- History & Thresholds ---
    private final LinkedList<Double> loadHistory = new LinkedList<>();
    private static final int HISTORY_SIZE = 5; // Average over the last 5 checks
    private static final double SCALE_UP_THRESHOLD = 0.70; // 70%
    private static final double SCALE_DOWN_THRESHOLD = 0.30; // 30%

    // --- Cooldown Management ---
    private long lastScaleTime = 0;
    private static final long COOLDOWN_MS = 50000; // Wait 60 seconds after scaling before scaling again

    // --- Local Testing Variables ---
    private static final ConcurrentHashMap<String, Process> localProcesses = new ConcurrentHashMap<>();
    private static final int[] LOCAL_PORTS = {8001, 8002, 8003, 8004};

    public void start() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    evaluateClusterLoad();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void evaluateClusterLoad() {
        int activeVmCount = 0;
        long totalInFlight = 0;

        // Calculate current load
        for (VmState state : LoadBalancer.clusterState.values()) {
            if (!state.isDraining) {
                activeVmCount++;
                totalInFlight += state.inFlightComplexity.get();
            }
        }

        long totalCapacity = activeVmCount * LoadBalancer.MAX_COMPLEXITY_PER_VM;
        double currentLoad = totalCapacity == 0 ? 1.0 : (double) totalInFlight / totalCapacity;

        // Update history for rolling average
        synchronized (loadHistory) {
            loadHistory.add(currentLoad);
            if (loadHistory.size() > HISTORY_SIZE) {
                loadHistory.removeFirst();
            }
        }

        double averageLoad = getAverageLoad();
        System.out.println(String.format("[AutoScaler] Current: %.2f%% | 50s Average: %.2f%% | Active VMs: %d",
                currentLoad * 100, averageLoad * 100, activeVmCount));

        // Enforce Cooldown
        if (System.currentTimeMillis() - lastScaleTime < COOLDOWN_MS) {
            System.out.println("[AutoScaler] In cooldown period. Skipping scaling actions.");
            return;
        }

        // Apply Scaling Thresholds
        if (averageLoad > SCALE_UP_THRESHOLD) {
            scaleUp();
        } else if (averageLoad < SCALE_DOWN_THRESHOLD && activeVmCount > 1) {
            scaleDown();
        }
    }

    private double getAverageLoad() {
        synchronized (loadHistory) {
            if (loadHistory.isEmpty()) return 0.0;
            double sum = 0;
            for (Double load : loadHistory) sum += load;
            return sum / loadHistory.size();
        }
    }

    private void scaleUp() {
        lastScaleTime = System.currentTimeMillis(); // Instantly start the cooldown timer so we don't spam requests

        if (LoadBalancer.IS_LOCAL_MODE) {
            for (int port : LOCAL_PORTS) {
                String host = "localhost:" + port;
                if (!LoadBalancer.clusterState.containsKey(host)) {
                    System.out.println("[AutoScaler] Scaling UP: Booting local worker on port " + port);
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                                "java",
                                "-noverify",
                                "-javaagent:lab-javassist/target/JavassistWrapper-1.0.0-SNAPSHOT-jar-with-dependencies.jar=ComplexityEstimator:pt.ulisboa.tecnico.cnv:output",
                                "-cp", "webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar",
                                "pt.ulisboa.tecnico.cnv.webserver.WebServer", String.valueOf(port)
                        );
                        pb.inheritIO();
                        Process process = pb.start();

                        localProcesses.put(host, process);
                        LoadBalancer.clusterState.put(host, new VmState(host));

                        // Small artificial delay to simulate boot time
                        Thread.sleep(3000);
                        System.out.println("[AutoScaler] Worker " + host + " is InService.");
                        return;
                    } catch (Exception e) {
                        System.err.println("[AutoScaler] Failed to boot local worker.");
                    }
                }
            }
            System.out.println("[AutoScaler] Max local workers reached. Cannot scale up.");
        } else {
            // AWS MODE: Call AWS SDK to launch EC2 instance with Health Check Poller
            System.out.println("[AutoScaler] Scaling UP: Requesting new EC2 instance...");

            // Run the boot and polling process in a background thread
            new Thread(() -> {
                String instanceIdToKillIfFailed = null;
                try {
                    // Read dynamically from OS environment
                    String workerAmi = System.getenv("WORKER_AMI");
                    String keyPair = System.getenv("AWS_KEYPAIR_NAME");
                    String securityGroup = System.getenv("AWS_SECURITY_GROUP");
                    String regionStr = System.getenv("AWS_DEFAULT_REGION");

                    Region region = (regionStr != null) ? Region.of(regionStr) : Region.EU_WEST_2;
                    Ec2Client ec2 = Ec2Client.builder().region(region).build();

                    // 1. Request the Instance
                    RunInstancesRequest runRequest = RunInstancesRequest.builder()
                        .imageId(workerAmi)
                        .instanceType(InstanceType.T3_MICRO)
                        .keyName(keyPair)
                        .securityGroupIds(securityGroup)
                        .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("CNV-Worker-Role").build())
                        .minCount(1)
                        .maxCount(1)
                        .build();

                    RunInstancesResponse response = ec2.runInstances(runRequest);
                    String instanceId = response.instances().get(0).instanceId();
                    instanceIdToKillIfFailed = instanceId; // Save this in case of boot failure
                    
                    String privateIp = response.instances().get(0).privateIpAddress(); 
                    String host = privateIp + ":8000"; 
                    
                    System.out.println("[AutoScaler] VM " + instanceId + " requested. Starting Health Check Poller for " + host + "...");
                    
                    // 2. Health Check Poller
                    boolean isHealthy = false;
                    int maxAttempts = 90; // 90 attempts * 2 seconds = 3 minutes max wait time
                    
                    for (int i = 0; i < maxAttempts; i++) {
                        try {
                            // Ping the root or /test endpoint
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://" + host + "/test"))
                                    .timeout(Duration.ofSeconds(1)) // Fast timeout so it doesn't hang
                                    .GET().build();
                            
                            // If this succeeds without throwing an exception, the Java server is ALIVE!
                            HttpResponse<String> healthResp = LoadBalancer.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            
                            if (healthResp.statusCode() == 200) {
                                isHealthy = true;
                                break; // Exit the polling loop immediately!
                            }
                        } catch (Exception ignored) {
                            // Connection refused or timeout expected while Linux/Java is booting.
                        }
                        Thread.sleep(2000); // Wait 2 seconds before pinging again
                    }
                    
                    // 3. Handle the Result
                    if (isHealthy) {
                        VmState newState = new VmState(host);
                        newState.setInstanceId(instanceId); 

                        // Add to Load Balancer routing table instantly
                        LoadBalancer.clusterState.put(host, newState);
                        System.out.println("[AutoScaler] ✅ SUCCESS! Worker " + host + " is healthy and accepting traffic!");
                    } else {
                        System.err.println("[AutoScaler] ❌ TIMEOUT: Worker " + host + " failed to boot after 3 minutes. Terminating it.");
                        // Terminate the broken instance so it doesn't cost money
                        TerminateInstancesRequest termReq = TerminateInstancesRequest.builder().instanceIds(instanceIdToKillIfFailed).build();
                        ec2.terminateInstances(termReq);
                    }
                    
                } catch (Exception e) {
                    System.err.println("[AutoScaler] Failed to launch AWS instance: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void scaleDown() {
        String hostToKill = null;
        long lowestLoad = Long.MAX_VALUE;

        // Find the least busy worker to drain
        for (Map.Entry<String, VmState> entry : LoadBalancer.clusterState.entrySet()) {
            if (!entry.getValue().isDraining && entry.getValue().inFlightComplexity.get() < lowestLoad) {
                lowestLoad = entry.getValue().inFlightComplexity.get();
                hostToKill = entry.getKey();
            }
        }

        if (hostToKill != null) {
            System.out.println("[AutoScaler] Scaling DOWN: Draining worker " + hostToKill);
            lastScaleTime = System.currentTimeMillis();

            // Mark as draining so the Load Balancer stops routing new traffic to it
            LoadBalancer.clusterState.get(hostToKill).isDraining = true;
            terminateWorker(hostToKill);
        }
    }

    // Public so the LoadBalancer can force-kill a broken VM (Fault Masking)
    public void terminateWorker(String targetHost) {
        if (LoadBalancer.IS_LOCAL_MODE) {
            if (localProcesses.containsKey(targetHost)) {
                localProcesses.get(targetHost).destroy();
                localProcesses.remove(targetHost);
                System.out.println("[AutoScaler] Local process " + targetHost + " destroyed.");
            }
        } else {
            // AWS MODE: Call AWS SDK to terminate EC2 instance
            System.out.println("[AutoScaler] Terminating AWS EC2 worker at: " + targetHost);

            VmState state = LoadBalancer.clusterState.get(targetHost);
            if (state != null && state.getInstanceId() != null) {
                String instanceIdToKill = state.getInstanceId();

                String regionStr = System.getenv("AWS_DEFAULT_REGION");
                Region region = (regionStr != null) ? Region.of(regionStr) : Region.EU_WEST_2;
                Ec2Client ec2 = Ec2Client.builder().region(region).build();

                try {
                    TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                        .instanceIds(instanceIdToKill)
                        .build();

                    ec2.terminateInstances(request);
                    System.out.println("[AutoScaler] Successfully sent termination request for: " + instanceIdToKill);
                } catch (Exception e) {
                    System.err.println("[AutoScaler] Failed to terminate instance: " + e.getMessage());
                }
            }
        }
        LoadBalancer.clusterState.remove(targetHost);
    }

    public void forceScaleUp() {
        // Prevent spamming scale-ups in a tight loop!
        if (System.currentTimeMillis() - lastScaleTime < COOLDOWN_MS) {
            System.out.println("[AutoScaler] Force Scale-Up requested, but in cooldown. Waiting for current boot.");
            return;
        }
        System.out.println("[AutoScaler] Force Scale-Up Triggered by Load Balancer!");
        scaleUp(); 
    }
}