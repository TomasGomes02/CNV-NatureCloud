package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.loadbalancer.utils.KnnPredictor;
import pt.ulisboa.tecnico.cnv.loadbalancer.utils.MetricsStorage;
import pt.ulisboa.tecnico.cnv.loadbalancer.utils.VmState;
import pt.ulisboa.tecnico.cnv.loadbalancer.utils.DataPoint;
import pt.ulisboa.tecnico.cnv.common.SignatureUtils; // <-- NEW SHARED MODULE

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.core.SdkBytes;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;

public class LoadBalancer {
    public static final boolean IS_LOCAL_MODE = false;

    public static final ConcurrentHashMap<String, VmState> clusterState = new ConcurrentHashMap<>();
    public static final long MAX_COMPLEXITY_PER_VM = 2000000000L;
    public static final long MAX_LAMBDA_COMPLEXITY = 500000000L;

    public static final KnnPredictor predictor = new KnnPredictor(5);
    public static final ConcurrentHashMap<String, Long> exactMatchCache = new ConcurrentHashMap<>();
    public static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final MetricsStorage storage = new MetricsStorage(IS_LOCAL_MODE);

    private static final Region REGION = Region.EU_WEST_2;

    private static final DynamoDbClient dynamo = DynamoDbClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .region(REGION)
            .build();

    private static final AutoScaler autoScaler = new AutoScaler();

    public static void main(String[] args) throws IOException {
        int lbPort = IS_LOCAL_MODE ? 8080 : 80;
        HttpServer server = HttpServer.create(new InetSocketAddress(lbPort), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", new ProxyHandler());
        server.createContext("/fractals", new ProxyHandler());
        server.createContext("/dna", new ProxyHandler());
        server.createContext("/grayscott", new ProxyHandler());
        server.createContext("/test", new ProxyHandler());
        server.createContext("/metrics", new MetricsReceiverHandler());

        // We load the data from CSV first, then fill in any gaps from DynamoDB
        loadInitialMetrics();
        preloadKnnFromDynamoDB();

        server.start();
        System.out.println("Custom Load Balancer started on port " + lbPort);

        autoScaler.start();
    }

    public static void loadInitialMetrics() {
        File csvFile = new File(IS_LOCAL_MODE ? "local_metrics.csv" : "/home/ec2-user/local_metrics.csv");

        if (!csvFile.exists()) {
            System.out.println("[LoadBalancer] No local metrics.csv found. Starting fresh.");
            return;
        }

        System.out.println("[LoadBalancer] Loading historical metrics from local file...");

        List<WriteRequest> writeBuffer = new ArrayList<>();
        int totalPushed = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String cleanLine = line.replace("\"", "");

                String[] columns = cleanLine.split(",");

                if (columns.length >= 4) {
                    String cacheKey = columns[0];
                    String requestType = columns[1];
                    String params = columns[2];
                    long complexity = Long.parseLong(columns[3]);

                    Map<String, String> paramsMap = SignatureUtils.queryToMap(params);
                    double[] features = SignatureUtils.extractFeatures(requestType, paramsMap);

                    exactMatchCache.put(cacheKey, complexity);
                    predictor.addDataPoint(new DataPoint(cacheKey, features, complexity));

                    Map<String, AttributeValue> item = Map.of(
                            "requestId", AttributeValue.fromS(cacheKey),
                            "requestType", AttributeValue.fromS(requestType),
                            "params", AttributeValue.fromS(params),
                            "complexity", AttributeValue.fromN(String.valueOf(complexity))
                    );

                    PutRequest putRequest = PutRequest.builder().item(item).build();
                    writeBuffer.add(WriteRequest.builder().putRequest(putRequest).build());

                    if (writeBuffer.size() == 25) {
                        flushBufferToDynamo(writeBuffer);
                        totalPushed += 25;
                        writeBuffer.clear();
                    }
                }
            }

            if (!writeBuffer.isEmpty()) {
                flushBufferToDynamo(writeBuffer);
                totalPushed += writeBuffer.size();
                writeBuffer.clear();
            }

            System.out.println("[LoadBalancer] Successfully batched and pushed " + totalPushed + " metrics to DynamoDB!");

        } catch (Exception e) {
            System.err.println("[LoadBalancer] Failed to read metrics file: " + e.getMessage());
        }
    }

    private static void flushBufferToDynamo(List<WriteRequest> buffer) {
        try {
            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(Map.of("CNVMetrics", buffer))
                    .build();

            dynamo.batchWriteItem(batchRequest);
        } catch (Exception e) {
            System.err.println("[LoadBalancer] Batch write failed: " + e.getMessage());
        }
    }

    private static void preloadKnnFromDynamoDB() {
        System.out.println("[LoadBalancer] Pre-loading KNN Predictor from DynamoDB...");
        try {
            int count = 0;
            // This will hold the pagination token
            Map<String, AttributeValue> lastKeyEvaluated = null;

            do {
                ScanRequest scanRequest = ScanRequest.builder()
                        .tableName("CNVMetrics")
                        .exclusiveStartKey(lastKeyEvaluated)
                        .build();

                ScanResponse response = dynamo.scan(scanRequest);

                for (Map<String, AttributeValue> item : response.items()) {
                    String cacheKey = item.get("requestId").s();
                    long complexity = Long.parseLong(item.get("complexity").n());
                    String type = item.get("requestType").s();
                    String rawParams = item.get("params").s();

                    // Use shared module!
                    Map<String, String> paramsMap = pt.ulisboa.tecnico.cnv.common.SignatureUtils.queryToMap(rawParams);
                    double[] features = pt.ulisboa.tecnico.cnv.common.SignatureUtils.extractFeatures(type, paramsMap);

                    exactMatchCache.put(cacheKey, complexity);
                    predictor.addDataPoint(new DataPoint(cacheKey, features, complexity));
                    count++;
                }

                // Grab the token for the next page. If it's empty, we've reached the end!
                lastKeyEvaluated = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;

            } while (lastKeyEvaluated != null && !lastKeyEvaluated.isEmpty());

            System.out.println("[LoadBalancer] Successfully loaded " + count + " historical data points into KNN.");
        } catch (Exception e) {
            System.err.println("[LoadBalancer] WARNING: Failed to pre-load from DynamoDB. KNN is starting cold. " + e.getMessage());
        }
    }

    static class ProxyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            String type = path.replace("/", "").toLowerCase();
            if (type.isEmpty()) type = "root";

            Map<String, String> paramsMap = SignatureUtils.queryToMap(query);
            double[] knnFeatures = SignatureUtils.extractFeatures(type, paramsMap);
            String cacheKey = SignatureUtils.generateCacheKey(type, paramsMap);

            StringBuilder safeQuery = new StringBuilder();
            if (!paramsMap.isEmpty()) {
                safeQuery.append("?");
                for (Map.Entry<String, String> entry : paramsMap.entrySet()) {
                    safeQuery.append(entry.getKey())
                            .append("=")
                            .append(java.net.URLEncoder.encode(entry.getValue(), UTF_8))
                            .append("&");
                }
                safeQuery.setLength(safeQuery.length() - 1);
            }

            String fullPath = path + safeQuery;
            long estimatedComplexity;

            if (exactMatchCache.containsKey(cacheKey)) {
                estimatedComplexity = exactMatchCache.get(cacheKey);
                System.out.println("[" + type.toUpperCase() + "] CACHE HIT Complexity: " + estimatedComplexity);
            } else {
                estimatedComplexity = predictor.estimate(knnFeatures);
                System.out.println("[" + type.toUpperCase() + "] KNN Predicted Complexity: " + estimatedComplexity);
            }

            String responseBody = null;
            int code = 200;

            // Increase attempts to give time for forced EC2 boots
            for (int attempt = 1; attempt <= 6; attempt++) {
                String targetHost;
                VmState state;

                synchronized (clusterState) {
                    targetHost = selectBestWorker(estimatedComplexity);

                    if (targetHost == null) {
                        // FEATURE 4: Lambda Cost Ceiling
                        if (estimatedComplexity > MAX_LAMBDA_COMPLEXITY) {
                            System.out.println("Request TOO HEAVY for Lambda. Forcing EC2 scale up!");
                            autoScaler.forceScaleUp();
                            try { Thread.sleep(10000); } catch (Exception ignored) {} // wait 10s for boot progress
                            continue; // Retry loop to check if cluster has capacity now
                        }

                        System.out.println("Cluster full. Invoking Lambda for type: " + type);
                        String regionStr = System.getenv("AWS_DEFAULT_REGION");
                        Region currentRegion = (regionStr != null) ? Region.of(regionStr) : Region.EU_WEST_2;
                        LambdaClient lambdaClient = LambdaClient.builder().region(currentRegion).build();

                        try {
                            StringBuilder jsonBuilder = new StringBuilder("{");
                            for (Map.Entry<String, String> entry : paramsMap.entrySet()) {
                                String safeValue = entry.getValue()
                                        .replace("\\", "\\\\").replace("\"", "\\\"")
                                        .replace("\n", "\\n").replace("\r", "\\r");
                                jsonBuilder.append("\"").append(entry.getKey()).append("\":\"").append(safeValue).append("\",");
                            }
                            if (jsonBuilder.length() > 1) jsonBuilder.setLength(jsonBuilder.length() - 1);
                            jsonBuilder.append("}");

                            InvokeRequest invokeRequest = InvokeRequest.builder()
                                    .functionName(type + "-lambda")
                                    .payload(SdkBytes.fromUtf8String(jsonBuilder.toString()))
                                    .build();

                            InvokeResponse lambdaResponse = lambdaClient.invoke(invokeRequest);
                            responseBody = lambdaResponse.payload().asUtf8String();
                            System.out.println("Lambda successfully executed.");
                        } catch (Exception e) {
                            System.err.println("Lambda invocation failed!");
                            responseBody = "Error: Lambda failed to execute.";
                            code = 500;
                        }
                        break;
                    }

                    state = clusterState.get(targetHost);
                    state.inFlightComplexity.addAndGet(estimatedComplexity);
                }

                // FEATURE 3: Two-Stage Timeout & Health Checking
                try {
                    System.out.println("Routing to " + targetHost + " (Attempt " + attempt + ")");

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + targetHost + fullPath))
                            .GET().build();

                    // Send asynchronously so we can wait in stages
                    java.util.concurrent.CompletableFuture<HttpResponse<String>> future =
                            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

                    try {
                        // STAGE 1: Wait 20 seconds for the future to complete
                        HttpResponse<String> response = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                        responseBody = response.body();
                        break; // Success

                    } catch (java.util.concurrent.TimeoutException e) {
                        System.out.println("Worker " + targetHost + " timed out (Stage 1). Pinging health check...");

                        boolean isAlive = false;
                        try {
                            HttpRequest pingReq = HttpRequest.newBuilder()
                                    .uri(URI.create("http://" + targetHost + "/test"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET().build();
                            HttpResponse<String> pingResp = httpClient.send(pingReq, HttpResponse.BodyHandlers.ofString());
                            if (pingResp.statusCode() == 200) isAlive = true;
                        } catch (Exception pingEx) {
                            isAlive = false;
                        }

                        if (isAlive) {
                            System.out.println("Worker " + targetHost + " is alive but busy. Waiting longer (Stage 2)...");
                            try {
                                // STAGE 2: Wait on the EXACT SAME future for an extra 2 minutes
                                HttpResponse<String> response = future.get(2, java.util.concurrent.TimeUnit.MINUTES);
                                responseBody = response.body();
                                break; // Success
                            } catch (java.util.concurrent.TimeoutException ex2) {
                                System.err.println("Worker " + targetHost + " failed Stage 2 timeout! Marking dead.");
                                future.cancel(true); // Kill the background connection
                                autoScaler.terminateWorker(targetHost);
                            }
                        } else {
                            System.err.println("Worker " + targetHost + " failed health ping! Marking dead.");
                            future.cancel(true);
                            autoScaler.terminateWorker(targetHost);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Worker " + targetHost + " rejected connection! Masking error...");
                    autoScaler.terminateWorker(targetHost);
                } finally {
                    if (clusterState.containsKey(targetHost)) {
                        state.inFlightComplexity.addAndGet(-estimatedComplexity);
                    }
                }
            }

            if (responseBody == null) {
                responseBody = "Error: Service Unavailable after retries.";
                code = 503;
            }

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(code, responseBody.length());
            OutputStream os = exchange.getResponseBody();
            os.write(responseBody.getBytes());
            os.close();
        }

        private String selectBestWorker(long complexity) {
            String bestIp = null;
            long lowestLoad = Long.MAX_VALUE;

            for (Map.Entry<String, VmState> entry : clusterState.entrySet()) {
                VmState state = entry.getValue();
                if (state.isDraining) continue;

                long currentLoad = state.inFlightComplexity.get();
                long projectedLoad = currentLoad + complexity;

                if (projectedLoad <= MAX_COMPLEXITY_PER_VM && currentLoad < lowestLoad) {
                    lowestLoad = currentLoad;
                    bestIp = entry.getKey();
                }
            }

            if (bestIp == null && complexity >= MAX_COMPLEXITY_PER_VM) {
                lowestLoad = Long.MAX_VALUE;
                // Find the VM with the absolute lowest load
                for (Map.Entry<String, VmState> entry : clusterState.entrySet()) {
                    VmState state = entry.getValue();
                    if (!state.isDraining && state.inFlightComplexity.get() < lowestLoad) {
                        lowestLoad = state.inFlightComplexity.get();
                        bestIp = entry.getKey();
                    }
                }

                if (lowestLoad > (MAX_COMPLEXITY_PER_VM * 0.05)) {
                    bestIp = null;
                }
            }

            return bestIp;
        }
    }

    static class MetricsReceiverHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();

            if (query != null) {
                try {
                    // Use shared module!
                    Map<String, String> map = SignatureUtils.queryToMap(query);
                    String type = map.get("type");
                    long complexity = Long.parseLong(map.get("complexity"));
                    String cacheKey = SignatureUtils.generateCacheKey(type, map);

                    String canonicalParams = map.entrySet().stream()
                            .filter(e -> !e.getKey().equals("type") && !e.getKey().equals("complexity"))
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .reduce((a, b) -> a + "&" + b).orElse("");

                    if (!exactMatchCache.containsKey(cacheKey)) {
                        double[] features = SignatureUtils.extractFeatures(type, map);

                        exactMatchCache.put(cacheKey, complexity);
                        predictor.addDataPoint(new DataPoint(cacheKey, features, complexity));

                        System.out.println("[LoadBalancer] Ingested Local Metric: " + cacheKey + " -> " + complexity);
                    }
                } catch (Exception e) {
                    System.err.println("[LoadBalancer] Failed to parse metrics payload.");
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }
}