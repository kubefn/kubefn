package com.demo.shared;

/**
 * This class exists in BOTH group-a and group-b with DIFFERENT implementations.
 * The classloader isolation ensures they don't collide.
 *
 * Group A returns version "1.0" — the original implementation.
 * Group B returns version "2.0" — an incompatible change.
 *
 * In microservices, this works because each service has its own JVM.
 * In KubeFn, this works because each group has its own classloader.
 * Same isolation guarantee, zero serialization overhead.
 */
public class VersionInfo {
    public static String VERSION = "1.0";
    public static String AUTHOR = "Team Alpha";

    // This method exists in v1 but NOT in v2 (simulating breaking change)
    public static String legacyMethod() {
        return "This method only exists in v1";
    }
}
