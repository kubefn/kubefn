package com.kubefn.operator.crd;

import java.util.List;

public class KubeFnGroupStatus {

    private int readyReplicas;
    private List<String> loadedFunctions;
    private String revisionId;
    private String phase;

    public int getReadyReplicas() {
        return readyReplicas;
    }

    public void setReadyReplicas(int readyReplicas) {
        this.readyReplicas = readyReplicas;
    }

    public List<String> getLoadedFunctions() {
        return loadedFunctions;
    }

    public void setLoadedFunctions(List<String> loadedFunctions) {
        this.loadedFunctions = loadedFunctions;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
