package pt.ulisboa.tecnico.cnv.loadbalancer.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MetricsStorage {
    private boolean isLocalMode;

    private final List<DataPoint> localDb = new CopyOnWriteArrayList<>();

    public MetricsStorage(boolean isLocalMode) {
        this.isLocalMode = isLocalMode;
    }

    public void addLocalMetric(DataPoint dp) {
        localDb.add(dp);
    }

    public List<DataPoint> fetchRecentMetrics() {
        if (isLocalMode) {
            // LOCAL MOCK: Return some fake historical data to train the KNN
            List<DataPoint> snapshot = new ArrayList<>(localDb);

            return snapshot;
        } else {
            // AWS MODE: TODO - Use DynamoDbClient to scan the CNVMetrics table
            return new ArrayList<>();
        }
    }
}
