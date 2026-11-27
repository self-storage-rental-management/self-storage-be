package com.storagehub.config;

import java.util.UUID;

public final class H2Functions {
    private H2Functions() {}

    public static String binToUuid(byte[] value) {
        if (value == null) return null;
        var buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }
}
