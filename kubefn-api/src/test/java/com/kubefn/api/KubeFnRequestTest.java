package com.kubefn.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubeFnRequestTest {

    @Test
    void queryParamReturnsValueWhenPresent() {
        var request = new KubeFnRequest("GET", "/test", "",
                Map.of(), Map.of("name", "KubeFn"), null);
        assertEquals("KubeFn", request.queryParam("name").orElse(""));
    }

    @Test
    void queryParamReturnsEmptyWhenMissing() {
        var request = new KubeFnRequest("GET", "/test", "",
                Map.of(), Map.of(), null);
        assertTrue(request.queryParam("name").isEmpty());
    }

    @Test
    void headerReturnsValueWhenPresent() {
        var request = new KubeFnRequest("POST", "/test", "",
                Map.of("content-type", "application/json"), Map.of(), null);
        assertEquals("application/json", request.header("content-type").orElse(""));
    }

    @Test
    void bodyAsStringConvertsBytes() {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        var request = new KubeFnRequest("POST", "/test", "",
                Map.of(), Map.of(), body);
        assertEquals("hello world", request.bodyAsString());
    }

    @Test
    void bodyAsStringReturnsEmptyForNull() {
        var request = new KubeFnRequest("GET", "/test", "",
                Map.of(), Map.of(), null);
        assertEquals("", request.bodyAsString());
    }
}
