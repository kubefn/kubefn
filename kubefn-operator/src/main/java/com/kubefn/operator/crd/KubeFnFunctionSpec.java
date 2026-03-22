package com.kubefn.operator.crd;

/**
 * Spec for a KubeFnFunction CRD.
 * Defines a single function within a group.
 */
public class KubeFnFunctionSpec {

    private String groupRef;
    private String className;
    private RouteSpec route;
    private SourceSpec source;

    public String getGroupRef() { return groupRef; }
    public void setGroupRef(String groupRef) { this.groupRef = groupRef; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public RouteSpec getRoute() { return route; }
    public void setRoute(RouteSpec route) { this.route = route; }

    public SourceSpec getSource() { return source; }
    public void setSource(SourceSpec source) { this.source = source; }

    public static class RouteSpec {
        private String path;
        private String[] methods = {"GET", "POST"};

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String[] getMethods() { return methods; }
        public void setMethods(String[] methods) { this.methods = methods; }
    }

    public static class SourceSpec {
        private String type = "configmap"; // configmap | pvc
        private ConfigMapRef configMapRef;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public ConfigMapRef getConfigMapRef() { return configMapRef; }
        public void setConfigMapRef(ConfigMapRef configMapRef) { this.configMapRef = configMapRef; }
    }

    public static class ConfigMapRef {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
