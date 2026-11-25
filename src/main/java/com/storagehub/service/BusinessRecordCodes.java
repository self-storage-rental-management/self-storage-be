package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Codes are display identifiers. UUIDs and stable overdue references remain the API keys. */
@Service @RequiredArgsConstructor
public class BusinessRecordCodes {
    private final JdbcTemplate jdbc;
    private static final int CODE_MIN=100_000;
    private static final int CODE_RANGE=900_000;
    private static final int MAX_ATTEMPTS=25;
    private static final DateTimeFormatter PERIOD=DateTimeFormatter.ofPattern("yyMM").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final SecureRandom SECURE_RANDOM=new SecureRandom();
    // Old notices retain their original text for idempotency. Resolve only dossier references.
    private static final java.util.regex.Pattern NOTICE_RECORD = java.util.regex.Pattern.compile(
        "(?iu)(Hợp đồng thuê|Hồ sơ thuê|Hồ sơ trả kho|Hồ sơ) ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    public String noticeText(String content) {
        if (content == null) return null;
        return NOTICE_RECORD.matcher(content).replaceAll(match -> {
            String label = match.group(1);
            String type = label.endsWith("thuê") ? "TH" : "TK";
            String id = match.group(2).toLowerCase(Locale.ROOT);
            String code = find(type + ":" + id);
            return label + " " + (code == null ? "HS-" + id.substring(0, 8).toUpperCase(Locale.ROOT) : code);
        });
    }

    @Transactional(readOnly=true)
    public String find(String key) {
        return jdbc.query("select display_code from business_record_codes where resource_key=?",(rs,i)->rs.getString(1),key)
            .stream().findFirst().orElse(null);
    }
    @Transactional(readOnly=true)
    public Map<String,String> findAll(List<String> keys) {
        if (keys.isEmpty()) return Map.of();
        var query = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        return query.query("select resource_key, display_code from business_record_codes where resource_key in (:keys)",
            Map.of("keys", keys), rs -> {
                Map<String,String> result = new HashMap<>();
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                return result;
            });
    }
    @Transactional(readOnly=true)
    public String resource(String type,String code) {
        if(code==null)return null;
        return jdbc.query("select resource_key from business_record_codes where type_code=? and display_code=?",
            (rs,i)->rs.getString(1),type,code.trim().toUpperCase(Locale.ROOT)).stream().findFirst().orElse(null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String assign(String type,String key,Instant recordedAt) {
        if(!Set.of("GH","HT","QH","TH","TK","NV","HP").contains(type)||key==null||key.isBlank()||key.length()>120||recordedAt==null)
            throw ApiExceptions.conflict("Thông tin cấp mã hồ sơ không hợp lệ.");
        String old=find(key);if(old!=null)return old;
        String period=PERIOD.format(recordedAt);
        for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
            String code=String.format(Locale.ROOT,"SH-%06d",CODE_MIN+SECURE_RANDOM.nextInt(CODE_RANGE));
            jdbc.update("""
                INSERT INTO business_record_codes(resource_key, type_code, period, display_code, recorded_at)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_code = display_code
                """, key, type, period, code, java.sql.Timestamp.from(recordedAt));
            // A locking read sees the winner even when the caller already established an older snapshot.
            String assigned = jdbc.query("SELECT display_code FROM business_record_codes WHERE resource_key=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, key);
            if (assigned != null) return assigned;
        }
        throw ApiExceptions.conflict("Không thể cấp mã hồ sơ duy nhất.");
    }
}
