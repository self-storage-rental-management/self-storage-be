package com.storagehub.common.api;

public final class CorrelationIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdContext() {
    }

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
    }

    public static String current() {
        String value = CURRENT.get();
        return value == null ? "unavailable" : value;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
