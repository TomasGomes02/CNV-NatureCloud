package pt.ulisboa.tecnico.cnv.loadbalancer.utils;

public class DataPoint {
    public final String signature;
    public final double[] params;
    public final long complexity;

    public DataPoint(String signature, double[] params, long complexity) {
        this.signature = signature;
        this.params = params;
        this.complexity = complexity;
    }
}
