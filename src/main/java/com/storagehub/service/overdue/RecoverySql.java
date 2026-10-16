package com.storagehub.service.overdue;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

final class RecoverySql {
    private RecoverySql() {}
    static byte[] bytes(UUID id){return id==null?null:ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    static UUID uuid(byte[] bytes){if(bytes==null)return null;var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
    static Instant instant(Timestamp at){return at==null?null:at.toInstant();}
}
