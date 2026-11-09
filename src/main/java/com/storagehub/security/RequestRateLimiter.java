package com.storagehub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RequestRateLimiter {
    private final JdbcTemplate jdbc;

    @Transactional
    public boolean allow(String scope, String identity, int maximum, long windowSeconds) {
        Instant now = Instant.now();
        long window = now.getEpochSecond() / windowSeconds * windowSeconds;
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((scope + ":" + identity).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        Timestamp expiry = Timestamp.from(Instant.ofEpochSecond(window + windowSeconds));
        jdbc.update("INSERT IGNORE INTO request_rate_limits (key_hash,window_start,request_count,expires_at) VALUES (?,?,0,?)", key, window, expiry);
        var rows = jdbc.query("SELECT window_start,request_count FROM request_rate_limits WHERE key_hash=? FOR UPDATE",
            (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, key);
        long count = rows.getFirst()[0] == window ? rows.getFirst()[1] + 1 : 1;
        jdbc.update("UPDATE request_rate_limits SET window_start=?,request_count=?,expires_at=? WHERE key_hash=?", window, count, expiry, key);
        return count <= maximum;
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanUp() { jdbc.update("DELETE FROM request_rate_limits WHERE expires_at<?", Timestamp.from(Instant.now())); }
}
