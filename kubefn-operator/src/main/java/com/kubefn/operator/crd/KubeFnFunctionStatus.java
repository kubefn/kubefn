package com.kubefn.operator.crd;

import java.util.ArrayList;
import java.util.List;

/**
 * Status of a KubeFnFunction CRD.
 */
public class KubeFnFunctionStatus {

    private boolean loaded;
    private String lastDeployed;
    private List<String> errors = new ArrayList<>();

    public boolean isLoaded() { return loaded; }
    public void setLoaded(boolean loaded) { this.loaded = loaded; }

    public String getLastDeployed() { return lastDeployed; }
    public void setLastDeployed(String lastDeployed) { this.lastDeployed = lastDeployed; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
}
