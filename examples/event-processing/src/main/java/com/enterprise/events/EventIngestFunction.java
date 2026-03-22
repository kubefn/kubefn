package com.enterprise.events;

import io.kubefn.api.*;

import java.util.*;

/**
 * Normalizes incoming IoT/telemetry events into a canonical schema.
 * Handles multiple device protocols (MQTT-style, HTTP sensor push, legacy CSV)
 * and produces a unified event envelope.
 */
@FnRoute(path = "/events/ingest", methods = {"POST"})
@FnGroup("event-processor")
public class EventIngestFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final Set<String> REQUIRED_FIELDS = Set.of("deviceId", "eventType");
    private static final Set<String> VALID_EVENT_TYPES = Set.of(
            "temperature", "humidity", "pressure", "vibration",
            "power_consumption", "heartbeat", "error", "alert"
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String deviceId = request.queryParam("deviceId").orElse("");
        String eventType = request.queryParam("eventType").orElse("");
        String valueStr = request.queryParam("value").orElse("");
        String unit = request.queryParam("unit").orElse("");
        String sourceProtocol = request.header("X-Source-Protocol").orElse("http");

        // Try parsing body as fallback for CSV-style ingest: "deviceId,eventType,value,unit"
        if (deviceId.isEmpty() && !request.bodyAsString().isBlank()) {
            String[] parts = request.bodyAsString().split(",");
            if (parts.length >= 2) {
                deviceId = parts[0].trim();
                eventType = parts[1].trim();
                if (parts.length >= 3) valueStr = parts[2].trim();
                if (parts.length >= 4) unit = parts[3].trim();
                sourceProtocol = "csv";
            }
        }

        // Validation
        if (deviceId.isEmpty()) {
            return KubeFnResponse.badRequest(Map.of("error", "missing_device_id",
                    "message", "deviceId is required"));
        }
        if (!VALID_EVENT_TYPES.contains(eventType)) {
            return KubeFnResponse.badRequest(Map.of("error", "invalid_event_type",
                    "message", "Unknown event type: " + eventType,
                    "validTypes", VALID_EVENT_TYPES));
        }

        // Parse numeric value
        Double numericValue = null;
        if (!valueStr.isEmpty()) {
            try {
                numericValue = Double.parseDouble(valueStr);
            } catch (NumberFormatException e) {
                return KubeFnResponse.badRequest(Map.of("error", "invalid_value",
                        "message", "Value must be numeric: " + valueStr));
            }
        }

        // Auto-assign unit if not provided
        if (unit.isEmpty()) {
            unit = switch (eventType) {
                case "temperature" -> "celsius";
                case "humidity" -> "percent";
                case "pressure" -> "hPa";
                case "vibration" -> "mm/s";
                case "power_consumption" -> "kWh";
                default -> "none";
            };
        }

        // Build canonical event envelope
        String eventId = "EVT-" + System.nanoTime() % 1000000;
        long ingestTimestamp = System.currentTimeMillis();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("deviceId", deviceId);
        event.put("eventType", eventType);
        event.put("value", numericValue);
        event.put("unit", unit);
        event.put("sourceProtocol", sourceProtocol);
        event.put("ingestTimestamp", ingestTimestamp);
        event.put("quality", numericValue != null ? "measured" : "status_only");
        event.put("raw", request.bodyAsString().isBlank() ? null : request.bodyAsString());

        ctx.heap().publish("event:ingested", event, Map.class);
        return KubeFnResponse.ok(event);
    }
}
