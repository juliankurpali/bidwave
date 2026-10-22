package com.bidwave.util;

import java.util.UUID;

/**
 * ID helpers for bidId and correlationId.
 */
public final class Ids {

    private Ids() {
    }

    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    public static String orNew(String maybe) {
        if (maybe == null || maybe.isBlank()) {
            return newUuid();
        }
        return maybe.trim();
    }
}
