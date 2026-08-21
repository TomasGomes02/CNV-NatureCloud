package pt.ulisboa.tecnico.cnv.javassist.tools;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.expr.ExprEditor;
import javassist.expr.NewArray;
import javassist.expr.NewExpr;

import pt.ulisboa.tecnico.cnv.common.SignatureUtils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

public class ComplexityEstimator extends CodeDumper {

    private static final String TABLE_NAME = "CNVMetrics";
    private static final Region REGION = Region.EU_WEST_2;

    private static final DynamoDbClient dynamo = DynamoDbClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .region(REGION)
            .build();

    private static final List<WriteRequest> writeBuffer = Collections.synchronizedList(new ArrayList<>());
    private static final int FLUSH_INTERVAL_MS = 30000;

    static {
        Thread flusherThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                    flushBufferToDynamo();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        flusherThread.setDaemon(true);
        flusherThread.start();
        System.out.println("[ComplexityEstimator] Background DynamoDB batch flusher started.");
    }

    private static final ThreadLocal<Long> threadBasicBlocks = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> threadAllocations = ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<String> threadRequestType = ThreadLocal.withInitial(() -> "unknown");
    private static final ThreadLocal<String> threadRequestParams = ThreadLocal.withInitial(() -> "");
    private static final ThreadLocal<String> threadRequestUri = ThreadLocal.withInitial(() -> "");

    // Calibrated Weights for Compute Units (CU)
    private static long weightBasicBlocks = 5000L;
    private static long weightAllocations = 50000L;

    public ComplexityEstimator(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void addAllocations(long allocs) {
        threadAllocations.set(threadAllocations.get() + allocs);
    }

    public static void addBlocks(long blocks) {
        threadBasicBlocks.set(threadBasicBlocks.get() + blocks);
    }

    public static void resetCounter() {
        threadBasicBlocks.set(0L);
        threadAllocations.set(0L);
    }

    public static void setRequestContext(String requestType, String params, String uri) {
        threadRequestType.set(requestType == null ? "unknown" : requestType.toLowerCase());
        threadRequestParams.set(params == null ? "" : params);
        threadRequestUri.set(uri == null ? "" : uri);
    }

    public static void publishMetric() {
        updateDynamicWeights();

        long blocks = threadBasicBlocks.get();
        long allocs = threadAllocations.get();
        String requestType = threadRequestType.get();
        String params = threadRequestParams.get();
        String host = threadRequestUri.get();

        Map<String, String> q = SignatureUtils.queryToMap(params);
        long rawWorkload = computeRawWorkload(requestType, q);
        long normalizedWorkload = normalizeWorkload(requestType, rawWorkload);

        long totalComplexity = (threadBasicBlocks.get() * weightBasicBlocks)
                + (threadAllocations.get() * weightBasicBlocks)
                + normalizedWorkload;

        System.out.println("[ComplexityEstimator] Type: " + requestType + " | Params: " + (!requestType.equals("dna") ? params : "dna"));
        System.out.println("[ComplexityEstimator] FINAL COMPLEXITY (CU): " + totalComplexity);
        System.out.println("[ComplexityEstimator] Host: " + host);
        System.out.println("[ComplexityEstimator] Raw Blocks: " + blocks);
        System.out.println("[ComplexityEstimator] Raw Allocs: " + allocs);
        System.out.println("[ComplexityEstimator] Hardware Status -> CPU Weight: " + weightBasicBlocks + " | RAM Weight: " + weightAllocations);

        if (!"unknown".equals(requestType)) {
            queueForDynamoDB(requestType, params, totalComplexity);

            try {
                String url = "http://" + host + ":8080/metrics?type="
                        + URLEncoder.encode(requestType, StandardCharsets.UTF_8)
                        + "&"
                        + params
                        + "&complexity="
                        + totalComplexity;

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                System.err.println("[ComplexityEstimator] Failed to ping local /metrics endpoint.");
            }
        }
    }

    private static void queueForDynamoDB(String requestType, String params, long complexity) {
        Map<String, String> parsedParams = SignatureUtils.queryToMap(params);
        String cacheKey = SignatureUtils.generateCacheKey(requestType, parsedParams);

        Map<String, AttributeValue> item = Map.of(
                "requestId", AttributeValue.fromS(cacheKey),
                "requestType", AttributeValue.fromS(requestType),
                "params", AttributeValue.fromS(params),
                "complexity", AttributeValue.fromN(String.valueOf(complexity))
        );

        PutRequest putRequest = PutRequest.builder().item(item).build();
        WriteRequest writeRequest = WriteRequest.builder().putRequest(putRequest).build();

        synchronized (writeBuffer) {
            writeBuffer.add(writeRequest);
            if (writeBuffer.size() >= 25) {
                flushBufferToDynamo();
            }
        }
    }

    private static void flushBufferToDynamo() {
        List<WriteRequest> batch;

        synchronized (writeBuffer) {
            if (writeBuffer.isEmpty()) {
                return;
            }
            int extractSize = Math.min(writeBuffer.size(), 25);
            batch = new ArrayList<>(writeBuffer.subList(0, extractSize));
            writeBuffer.subList(0, extractSize).clear();
        }

        try {
            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(Map.of(TABLE_NAME, batch))
                    .build();

            dynamo.batchWriteItem(batchRequest);
            System.out.println("[ComplexityEstimator] Successfully batch-flushed " + batch.size() + " metrics to DynamoDB.");
        } catch (AwsServiceException | SdkClientException e) {
            System.err.println("[ComplexityEstimator] Failed to batch write to DynamoDB: " + e.getMessage());
        }
    }

    private static long computeRawWorkload(String requestType, Map<String, String> q) {
        switch (requestType) {
            case "fractals":
                return fractalsWorkload(q);
            case "grayscott":
                return grayScottWorkload(q);
            case "dna":
                return dnaWorkload(q);
            default:
                return 0L;
        }
    }

    private static long normalizeWorkload(String requestType, long raw) {
        switch (requestType) {
            case "fractals":  return (long) (raw * 0.000002);
            case "grayscott": return (long) (raw * 0.0003);
            case "dna":       return (long) (raw * 1.5);
            default:          return raw;
        }
    }

    // Workload heuristic calculators — domain-specific raw estimates
    private static long fractalsWorkload(Map<String, String> q) {
        long w = parseLong(q.get("w"), 0L);
        long h = parseLong(q.get("h"), 0L);
        long iterations = parseLong(q.get("iterations"), 0L);
        if (w <= 0 || h <= 0 || iterations <= 0) return 0L;
        return w * h * iterations;
    }

    private static long grayScottWorkload(Map<String, String> q) {
        long size = parseLong(q.get("size"), 0L);
        long maxIterations = parseLong(q.get("maxIterations"), 0L);
        boolean stopOnExtinction = Boolean.parseBoolean(q.getOrDefault("stopOnExtinction", "false"));
        String seedMode = q.getOrDefault("seedMode", "center").toLowerCase();

        if (size <= 0 || maxIterations <= 0) return 0L;

        long base = size * size * maxIterations;
        if (stopOnExtinction) base = (long) (base * 0.75);

        switch (seedMode) {
            case "center": return base;
            case "ring":   return (long) (base * 1.10);
            case "stripe": return (long) (base * 1.20);
            default:       return base;
        }
    }

    private static long dnaWorkload(Map<String, String> q) {
        String seq1 = q.get("seq1");
        String seq2 = q.get("seq2");
        long minLength = parseLong(q.get("minLength"), 1L);
        boolean stopOnFirst = Boolean.parseBoolean(q.getOrDefault("stopOnFirst", "false"));

        long len1 = fastaLength(seq1);
        long len2 = fastaLength(seq2);

        if (len1 <= 0 || len2 <= 0) return 0L;

        long base = len1 * len2;
        long factor = stopOnFirst ? 1L : 3L;
        long divisor = Math.max(1L, minLength);

        return (base / divisor) * factor;
    }

    private static long fastaLength(String value) {
        if (value == null || value.isBlank()) return 0L;
        int idx = value.indexOf(':');
        if (idx >= 0 && idx < value.length() - 1) return value.substring(idx + 1).length();
        return value.length();
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value == null || value.isBlank()) return fallback;
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        String methodName = behavior.getMethodInfo().getName();

        if (methodName.equals("<init>") || methodName.equals("<clinit>") || methodName.equals("main")) {
            return;
        }

        behavior.instrument(new ExprEditor() {
            @Override
            public void edit(NewExpr e) throws CannotCompileException {
                e.replace("{ " + ComplexityEstimator.class.getName() + ".addAllocations(1L); $_ = $proceed($$); }");
            }

            @Override
            public void edit(NewArray a) throws CannotCompileException {
                a.replace("{ " + ComplexityEstimator.class.getName() + ".addAllocations(1L); $_ = $proceed($$); }");
            }
        });

        long estimatedBlocks = estimateMethodBlocks(behavior);

        if (behavior.getName().equals("handle")) {
            behavior.insertBefore(ComplexityEstimator.class.getName() + ".resetCounter();");
            behavior.insertAfter(ComplexityEstimator.class.getName() + ".publishMetric();", true);
        }

        if (estimatedBlocks > 0) {
            behavior.insertBefore(ComplexityEstimator.class.getName() + ".addBlocks(" + estimatedBlocks + "L);");
        }
    }

    private long estimateMethodBlocks(CtBehavior behavior) {
        try {
            MethodInfo info = behavior.getMethodInfo2();
            if (info == null || info.getCodeAttribute() == null) return 0L;
            ControlFlow flow = new ControlFlow(behavior.getDeclaringClass(), info);
            ControlFlow.Block[] blocks = flow.basicBlocks();
            return blocks == null ? 0L : blocks.length;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void updateDynamicWeights() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

            // CPU usage returns 0.0 to 1.0 (or -1 if unavailable)
            double cpuLoad = osBean.getSystemCpuLoad();
            if (cpuLoad < 0) cpuLoad = 0.0;

            // RAM usage
            long totalRam = osBean.getTotalPhysicalMemorySize();
            long freeRam = osBean.getFreePhysicalMemorySize();
            double ramUsage = (double) (totalRam - freeRam) / totalRam;

            // Dynamic Math:
            // CPU stressed -> Block weight scales from 1 up to 10
            weightBasicBlocks = 1L + (long) (cpuLoad * 9);

            // RAM stressed -> Allocation weight scales from 100 up to 1000
            weightAllocations = 100L + (long) (ramUsage * 900);

        } catch (Exception e) {
            // Fallback to base weights if JMX reading fails
            weightBasicBlocks = 1L;
            weightAllocations = 100L;
        }
    }
}