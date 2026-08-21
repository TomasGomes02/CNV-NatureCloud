package pt.ulisboa.tecnico.cnv.loadbalancer.utils;

import java.util.concurrent.atomic.AtomicLong;

public class VmState {
    public AtomicLong inFlightComplexity = new AtomicLong(0);
    public volatile boolean isDraining = false;
    public String ipAddress;
    
    // Field for Aws
    private String instanceId; 

    public VmState(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    // Methods for Aws
    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
}
