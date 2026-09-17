package com.example.platform.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/** Shared correlation-ID contract for the gateway and servlet services. */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-ID";
    public static final String LEGACY_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private CorrelationIds() {
    }

    /** Prefers the canonical header, accepts the legacy header, or generates a UUID. */
    public static String resolve(String supplied, String legacySupplied) {
        if (isSafe(supplied)) {
            return supplied;
        }
        if (isSafe(legacySupplied)) {
            return legacySupplied;
        }
        return UUID.randomUUID().toString();
    }

    public static boolean isSafe(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches();
    }
}
