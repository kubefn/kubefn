package com.enterprise.events;

import io.kubefn.api.*;

import java.util.*;

/**
 * Enriches raw events with device metadata, location information, and
 * ownership context. Looks up device registry and geo-location databases
 * to add operational context for downstream processing.
 */
@FnRoute(path = "/events/enrich", methods = {"POST"})
@FnGroup("event-processor")
public class EventEnrichFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated device registry
    private static final Map<String, Map<String, Object>> DEVICE_REGISTRY = Map.of(
            "SENSOR-001", Map.of(
                    "model", "TempHumidity-Pro-v3",
                    "manufacturer", "SensorCorp",
                    "firmwareVersion", "2.4.1",
                    "installDate", "2024-03-15",
                    "calibrationDue", "2025-03-15",
                    "location", Map.of("building", "Warehouse-A", "floor", 1, "zone", "Cold-Storage",
                            "lat", 41.8781, "lon", -87.6298, "city", "Chicago"),
                    "owner", "Logistics Division"
            ),
            "SENSOR-002", Map.of(
                    "model", "VibrationMon-X1",
                    "manufacturer", "IndustrialSense",
                    "firmwareVersion", "1.8.0",
                    "installDate", "2023-11-01",
                    "calibrationDue", "2024-11-01",
                    "location", Map.of("building", "Factory-B", "floor", 2, "zone", "Assembly-Line",
                            "lat", 37.7749, "lon", -122.4194, "city", "San Francisco"),
                    "owner", "Manufacturing Division"
            ),
            "SENSOR-003", Map.of(
                    "model", "PowerMeter-Ind",
                    "manufacturer", "GridWatch",
                    "firmwareVersion", "3.1.2",
                    "installDate", "2024-01-20",
                    "calibrationDue", "2025-07-20",
                    "location", Map.of("building", "DataCenter-C", "floor", 0, "zone", "Server-Room-1",
                            "lat", 47.6062, "lon", -122.3321, "city", "Seattle"),
                    "owner", "IT Operations"
            ),
            "SENSOR-004", Map.of(
                    "model", "TempHumidity-Pro-v3",
                    "manufacturer", "SensorCorp",
                    "firmwareVersion", "2.4.1",
                    "installDate", "2024-06-10",
                    "calibrationDue", "2025-06-10",
                    "location", Map.of("building", "Warehouse-A", "floor", 1, "zone", "Ambient-Storage",
                            "lat", 41.8781, "lon", -87.6298, "city", "Chicago"),
                    "owner", "Logistics Division"
            )
    );

    // Operational thresholds per event type (for context)
    private static final Map<String, Map<String, Double>> OPERATIONAL_THRESHOLDS = Map.of(
            "temperature", Map.of("warningLow", -10.0, "warningHigh", 45.0, "criticalLow", -25.0, "criticalHigh", 60.0),
            "humidity", Map.of("warningLow", 20.0, "warningHigh", 80.0, "criticalLow", 10.0, "criticalHigh", 95.0),
            "vibration", Map.of("warningLow", 0.0, "warningHigh", 7.5, "criticalLow", 0.0, "criticalHigh", 15.0),
            "power_consumption", Map.of("warningLow", 0.0, "warningHigh", 500.0, "criticalLow", 0.0, "criticalHigh", 800.0),
            "pressure", Map.of("warningLow", 950.0, "warningHigh", 1050.0, "criticalLow", 900.0, "criticalHigh", 1100.0)
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var event = ctx.heap().get("event:ingested", Map.class).orElse(Map.of());

        String deviceId = (String) event.getOrDefault("deviceId", "unknown");
        String eventType = (String) event.getOrDefault("eventType", "unknown");

        Map<String, Object> enriched = new LinkedHashMap<>(event);

        // Enrich with device metadata
        Map<String, Object> deviceInfo = DEVICE_REGISTRY.get(deviceId);
        if (deviceInfo != null) {
            enriched.put("device", deviceInfo);
            enriched.put("deviceKnown", true);

            // Check calibration status
            String calibrationDue = (String) deviceInfo.get("calibrationDue");
            boolean calibrationOverdue = calibrationDue != null &&
                    calibrationDue.compareTo("2025-01-01") < 0;
            enriched.put("calibrationOverdue", calibrationOverdue);
            if (calibrationOverdue) {
                enriched.put("dataReliability", "degraded");
            } else {
                enriched.put("dataReliability", "normal");
            }
        } else {
            enriched.put("deviceKnown", false);
            enriched.put("dataReliability", "unknown_device");
            enriched.put("device", Map.of("warning", "Unregistered device: " + deviceId));
        }

        // Attach operational thresholds for context
        Map<String, Double> thresholds = OPERATIONAL_THRESHOLDS.get(eventType);
        if (thresholds != null) {
            enriched.put("thresholds", thresholds);
        }

        enriched.put("enrichedAt", System.currentTimeMillis());

        ctx.heap().publish("event:enriched", enriched, Map.class);
        return KubeFnResponse.ok(enriched);
    }
}
