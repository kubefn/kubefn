package com.demo.shared;

/**
 * DIFFERENT implementation from Group A's VersionInfo.
 * Same fully qualified class name, different behavior.
 *
 * Group B has:
 * - VERSION = "2.0" (vs "1.0" in Group A)
 * - AUTHOR = "Team Beta" (vs "Team Alpha")
 * - newFeature() method (doesn't exist in Group A)
 * - NO legacyMethod() (exists in Group A)
 *
 * Both run simultaneously in the same JVM. No collision.
 * This is the classloader isolation proof.
 */
public class VersionInfo {
    public static String VERSION = "2.0";
    public static String AUTHOR = "Team Beta";

    // This method exists in v2 but NOT in v1
    public static String newFeature() {
        return "This method only exists in v2";
    }
}
