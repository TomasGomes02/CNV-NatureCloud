package pt.ulisboa.tecnico.cnv.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class SignatureUtils {

    public static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return result;

        for (String param : query.split("&")) {
            if (param.isBlank()) continue;
            String[] entry = param.split("=");
            if (entry.length > 1) {
                try {
                    result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8.name()));
                } catch (Exception e) {
                    result.put(entry[0], entry[1]); // Fallback
                }
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }

    public static String generateCacheKey(String type, Map<String, String> params) {
        Map<String, String> sortedParams = new TreeMap<>(params);
        sortedParams.remove("type");
        sortedParams.remove("complexity");

        StringBuilder sigBuilder = new StringBuilder(type.toLowerCase()).append("-");
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            sigBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }

        if (sigBuilder.length() > 0 && sigBuilder.charAt(sigBuilder.length() - 1) == '&') {
            sigBuilder.setLength(sigBuilder.length() - 1);
        }

        return UUID.nameUUIDFromBytes(sigBuilder.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String generateCacheKeyFromQuery(String type, String query) {
        return generateCacheKey(type, queryToMap(query));
    }

    public static double[] extractFeatures(String type, Map<String, String> params) {
        try {
            switch (type) {
                case "fractals":
                    double w = Double.parseDouble(params.getOrDefault("w", "800"));
                    double h = Double.parseDouble(params.getOrDefault("h", "600"));
                    double i = Double.parseDouble(params.getOrDefault("iterations", "100"));
                    return new double[]{w, h, i};
                case "dna":
                    String seq1 = params.getOrDefault("seq1", "seq1:ATGC");
                    String seq2 = params.getOrDefault("seq2", "seq2:ATGC");
                    double len1 = seq1.contains(":") ? seq1.split(":")[1].length() : seq1.length();
                    double len2 = seq2.contains(":") ? seq2.split(":")[1].length() : seq2.length();
                    double minLength = Double.parseDouble(params.getOrDefault("minLength", "1"));
                    return new double[]{len1, len2, minLength};
                case "grayscott":
                    double size = Double.parseDouble(params.getOrDefault("size", "256"));
                    double maxIter = Double.parseDouble(params.getOrDefault("maxIterations", "5000"));
                    double f = Double.parseDouble(params.getOrDefault("f", "0.030"));
                    double k = Double.parseDouble(params.getOrDefault("k", "0.062"));
                    return new double[]{size, maxIter, f, k};
                default:
                    return new double[]{0, 0, 0};
            }
        } catch (Exception e) {
            System.err.println("Failed to extract features for " + type);
            return new double[]{0, 0, 0};
        }
    }
}