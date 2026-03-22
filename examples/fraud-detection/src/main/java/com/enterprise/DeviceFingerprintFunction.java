package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes device fingerprint and IP reputation to produce a device trust score.
 * Checks for VPN/proxy usage, device age, known-bad fingerprints, and geo anomalies.
 */
@FnRoute(path = "/fraud/device", methods = {"POST"})
@FnGroup("fraud-engine")
public class DeviceFingerprintFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Known suspicious IP ranges (simulated threat intelligence)
    private static final List<String> SUSPICIOUS_IP_PREFIXES = List.of(
            "10.0.0.", "192.168.99.", "172.16.0.", "45.33.", "185.220."
    );

    // Known VPN/proxy exit node patterns
    private static final List<String> VPN_INDICATORS = List.of(
            "vpn", "proxy", "tor", "relay"
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var txn = ctx.heap().get("fraud:transaction", Map.class).orElse(Map.of());
        String ipAddress = (String) txn.getOrDefault("ipAddress", "0.0.0.0");
        String deviceId = (String) txn.getOrDefault("deviceId", "unknown");
        String country = (String) txn.getOrDefault("country", "US");
        String userId = (String) txn.getOrDefault("userId", "unknown");

        double deviceScore = 0.0;
        var flags = new java.util.ArrayList<String>();

        // IP reputation check
        boolean suspiciousIp = SUSPICIOUS_IP_PREFIXES.stream()
                .anyMatch(ipAddress::startsWith);
        if (suspiciousIp) {
            deviceScore += 0.25;
            flags.add("suspicious_ip_range");
        }

        // VPN/Proxy detection (simulated — check device ID patterns)
        boolean vpnDetected = VPN_INDICATORS.stream()
                .anyMatch(ind -> deviceId.toLowerCase().contains(ind));
        if (vpnDetected) {
            deviceScore += 0.20;
            flags.add("vpn_proxy_detected");
        }

        // Device age heuristic — new devices are riskier
        long deviceHash = deviceId.hashCode() & 0xFFFFFFFFL;
        boolean newDevice = (deviceHash % 5) == 0; // ~20% chance simulated as new
        if (newDevice) {
            deviceScore += 0.15;
            flags.add("new_device");
        }

        // Geo-mismatch detection: IP geo vs stated country
        String ipGeo = resolveIpGeo(ipAddress);
        boolean geoMismatch = !ipGeo.equalsIgnoreCase(country);
        if (geoMismatch) {
            deviceScore += 0.30;
            flags.add("geo_mismatch:" + ipGeo + "_vs_" + country);
        }

        // Multiple accounts per device check (simulated)
        int accountsOnDevice = (int) (deviceHash % 4) + 1;
        if (accountsOnDevice > 2) {
            deviceScore += 0.10;
            flags.add("multi_account_device:" + accountsOnDevice);
        }

        // Cap score at 1.0
        deviceScore = Math.min(deviceScore, 1.0);

        String riskLevel;
        if (deviceScore < 0.2) riskLevel = "trusted";
        else if (deviceScore < 0.5) riskLevel = "moderate";
        else if (deviceScore < 0.8) riskLevel = "suspicious";
        else riskLevel = "high_risk";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("ipAddress", ipAddress);
        result.put("deviceScore", Math.round(deviceScore * 1000.0) / 1000.0);
        result.put("riskLevel", riskLevel);
        result.put("ipGeo", ipGeo);
        result.put("geoMismatch", geoMismatch);
        result.put("vpnDetected", vpnDetected);
        result.put("newDevice", newDevice);
        result.put("accountsOnDevice", accountsOnDevice);
        result.put("flags", flags);

        ctx.heap().publish("fraud:device", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private String resolveIpGeo(String ip) {
        // Simulated geo-IP resolution
        if (ip.startsWith("45.33.")) return "DE";
        if (ip.startsWith("185.220.")) return "RU";
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return "US";
        // Hash-based deterministic geo for demo
        int hash = Math.abs(ip.hashCode());
        String[] countries = {"US", "US", "US", "GB", "CA", "DE", "JP", "AU", "IN", "BR"};
        return countries[hash % countries.length];
    }
}
