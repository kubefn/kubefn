package com.kubefn.operator.crd;

import java.util.List;
import java.util.Map;

public class KubeFnGroupSpec {

    private String isolationMode = "shared-jvm";
    private int replicas = 1;
    private String image;
    private List<String> jvmArgs;
    private Map<String, String> config;
    private ResourceRequirements resources;

    public String getIsolationMode() {
        return isolationMode;
    }

    public void setIsolationMode(String isolationMode) {
        this.isolationMode = isolationMode;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public ResourceRequirements getResources() {
        return resources;
    }

    public void setResources(ResourceRequirements resources) {
        this.resources = resources;
    }

    public static class ResourceRequirements {
        private ResourceSpec requests;
        private ResourceSpec limits;

        public ResourceSpec getRequests() {
            return requests;
        }

        public void setRequests(ResourceSpec requests) {
            this.requests = requests;
        }

        public ResourceSpec getLimits() {
            return limits;
        }

        public void setLimits(ResourceSpec limits) {
            this.limits = limits;
        }
    }

    public static class ResourceSpec {
        private String cpu;
        private String memory;

        public String getCpu() {
            return cpu;
        }

        public void setCpu(String cpu) {
            this.cpu = cpu;
        }

        public String getMemory() {
            return memory;
        }

        public void setMemory(String memory) {
            this.memory = memory;
        }
    }
}
