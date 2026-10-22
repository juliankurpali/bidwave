package com.bidwave.api;

import com.bidwave.util.Json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP API (payload format 2.0) response helpers.
 */
public final class ApiResponses {

    private static final Map<String, String> JSON_HEADERS = Map.of(
            "content-type", "application/json",
            "access-control-allow-origin", "*",
            "access-control-allow-headers", "content-type,x-api-key",
            "access-control-allow-methods", "GET,POST,OPTIONS");

    private ApiResponses() {
    }

    public static Map<String, Object> ok(Object body) {
        return response(200, body);
    }

    public static Map<String, Object> created(Object body) {
        return response(201, body);
    }

    public static Map<String, Object> badRequest(String message) {
        return response(400, Map.of("error", message));
    }

    public static Map<String, Object> notFound(String message) {
        return response(404, Map.of("error", message));
    }

    public static Map<String, Object> serverError(String message) {
        return response(500, Map.of("error", message));
    }

    public static Map<String, Object> response(int statusCode, Object body) {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", statusCode);
        result.put("headers", JSON_HEADERS);
        result.put("body", body instanceof String ? body : Json.write(body));
        return result;
    }

    /**
     * Extract path parameter from HTTP API v2 event map.
     */
    @SuppressWarnings("unchecked")
    public static String pathParam(Map<String, Object> event, String name) {
        Object params = event.get("pathParameters");
        if (params instanceof Map<?, ?> map) {
            Object v = map.get(name);
            return v != null ? v.toString() : null;
        }
        return null;
    }

    public static String body(Map<String, Object> event) {
        Object body = event.get("body");
        if (body == null) {
            return null;
        }
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    public static String header(Map<String, Object> event, String name) {
        Object headers = event.get("headers");
        if (!(headers instanceof Map<?, ?> map)) {
            return null;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().toString().equalsIgnoreCase(name)) {
                return e.getValue() != null ? e.getValue().toString() : null;
            }
        }
        return null;
    }

    public static List<String> corsMethods() {
        return List.of("GET", "POST", "OPTIONS");
    }
}
