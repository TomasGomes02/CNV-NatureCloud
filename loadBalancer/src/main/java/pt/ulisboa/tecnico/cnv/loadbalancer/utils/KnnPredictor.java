package pt.ulisboa.tecnico.cnv.loadbalancer.utils;

import pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class KnnPredictor {
    private final int k;
    private final ConcurrentHashMap<String, DataPoint> knownData;

    public KnnPredictor(int k) {
        this.k = k;
        knownData = new ConcurrentHashMap<>();
    }

    public void addDataPoint(DataPoint dp) {
        knownData.put(dp.signature, dp);
    }

    public long estimate(double[] targetParams) {
        if (knownData.isEmpty()) {
            return LoadBalancer.MAX_COMPLEXITY_PER_VM / 100;
        }

        List<DistancePair> distances = new ArrayList<>();
        for (DataPoint point : knownData.values()) {
            double dist = calculateEuclideanDistance(targetParams, point.params);
            distances.add(new DistancePair(dist, point.complexity));
        }

        distances.sort(Comparator.comparingDouble(p -> p.distance));

        long sumComplexity = 0;
        int neighborsToConsider = Math.min(k, distances.size());

        for (int i=0; i < neighborsToConsider; i++) {
            sumComplexity += distances.get(i).complexity;
        }

        return sumComplexity / neighborsToConsider;
    }

    private double calculateEuclideanDistance(double[] p1, double[] p2) {
        double sum = 0;
        for (int i = 0; i < Math.min(p1.length, p2.length); i++) {
            sum += Math.pow(p1[i] - p2[i], 2);
        }
        return Math.sqrt(sum);
    }

    private static class DistancePair {
        double distance;
        long complexity;
        DistancePair(double distance, long complexity) {
            this.distance = distance;
            this.complexity = complexity;
        }
    }
}
