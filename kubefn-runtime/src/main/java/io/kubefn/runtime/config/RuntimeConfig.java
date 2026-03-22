package io.kubefn.runtime.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runtime configuration loaded from environment variables.
 * The organism's DNA.
 */
public record RuntimeConfig(
        int port,
        int adminPort,
        Path functionsDir,
        int maxConcurrencyPerGroup,
        long requestTimeoutMs,
        int maxRequestBodyBytes
) {

    public static RuntimeConfig fromEnv() {
        return new RuntimeConfig(
                intEnv("KUBEFN_PORT", 8080),
                intEnv("KUBEFN_ADMIN_PORT", 8081),
                Paths.get(stringEnv("KUBEFN_FUNCTIONS_DIR", "/var/kubefn/functions")),
                intEnv("KUBEFN_MAX_CONCURRENCY_PER_GROUP", 256),
                longEnv("KUBEFN_REQUEST_TIMEOUT_MS", 30_000),
                intEnv("KUBEFN_MAX_REQUEST_BODY_BYTES", 10 * 1024 * 1024) // 10MB
        );
    }

    private static String stringEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    private static long longEnv(String key, long defaultValue) {
        String val = System.getenv(key);
        return val != null ? Long.parseLong(val) : defaultValue;
    }
}
