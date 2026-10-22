package com.bidwave.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured JSON logging for CloudWatch.
 */
public final class Log {

    private Log() {
    }

    public static void info(String message, Map<String, Object> fields) {
        write("INFO", message, fields);
    }

    public static void warn(String message, Map<String, Object> fields) {
        write("WARN", message, fields);
    }

    public static void error(String message, Map<String, Object> fields) {
        write("ERROR", message, fields);
    }

    public static Map<String, Object> fields(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("fields requires even number of args");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static void write(String level, String message, Map<String, Object> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("level", level);
        entry.put("message", message);
        if (fields != null) {
            entry.putAll(fields);
        }
        System.out.println(Json.write(entry));
    }
}
