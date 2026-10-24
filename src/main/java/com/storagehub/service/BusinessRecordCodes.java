package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
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
    @Transactional(readOnly=true)
    public String find(String key) {
        return jdbc.query("select display_code from business_record_codes where resource_key=?",(rs,i)->rs.getString(1),key)
            .stream().findFirst().orElse(null);
    }
    @Transactional(readOnly=true)
    public String resource(String type,String code) {
        if(code==null)return null;
        return jdbc.query("select resource_key from business_record_codes where type_code=? and display_code=?",
            (rs,i)->rs.getString(1),type,code.trim().toUpperCase(Locale.ROOT)).stream().findFirst().orElse(null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String assign(String type,String key,Instant recordedAt) {
        if(!Set.of("GH","HT","QH").contains(type)||key==null||key.isBlank()||key.length()>120||recordedAt==null)
            throw ApiExceptions.conflict("Thông tin cấp mã hồ sơ không hợp lệ.");
        String old=find(key);if(old!=null)return old;
        String period=PERIOD.format(recordedAt);
        for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
            String code=String.format(Locale.ROOT,"SH-%06d",CODE_MIN+SECURE_RANDOM.nextInt(CODE_RANGE));
            try {
                jdbc.update("insert into business_record_codes(resource_key,type_code,period,display_code,recorded_at) values(?,?,?,?,?)",
                    key,type,period,code,java.sql.Timestamp.from(recordedAt));
                return code;
            } catch(DuplicateKeyException duplicate) {
                // A concurrent assignment for the same resource wins; a display-code collision gets another random candidate.
                old=find(key);if(old!=null)return old;
            }
        }
        throw ApiExceptions.conflict("Không thể cấp mã hồ sơ duy nhất.");
    }
}
