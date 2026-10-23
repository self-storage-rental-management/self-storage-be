package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Codes are display identifiers. UUIDs and stable overdue references remain the API keys. */
@Service @RequiredArgsConstructor
public class BusinessRecordCodes {
    private final JdbcTemplate jdbc;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter PERIOD=DateTimeFormatter.ofPattern("yyMM").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
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
        // Serialize allocation for each type/month before locking the registry. For GH the
        // sequence row is only a mutex: new codes use random numbers, not the counter.
        boolean randomCode="GH".equals(type);
        String update=randomCode?"serial_value=serial_value":"serial_value=least(serial_value+1,1000000)";
        jdbc.update("insert into business_record_code_sequences(type_code,period,serial_value) values(?,?,1) on duplicate key update "+update,type,period);
        long serial=jdbc.queryForObject("select serial_value from business_record_code_sequences where type_code=? and period=? for update",Long.class,type,period);
        old=jdbc.query("select display_code from business_record_codes where resource_key=? for update",(rs,i)->rs.getString(1),key).stream().findFirst().orElse(null);
        if(old!=null)return old;
        if(!randomCode&&serial>999999)throw ApiExceptions.conflict("Đã hết dải mã hồ sơ trong tháng.");
        String code=randomCode?randomRenewalCode(period):type+"-"+period+"-"+String.format(Locale.ROOT,"%06d",serial);
        jdbc.update("insert into business_record_codes(resource_key,type_code,period,display_code,recorded_at) values(?,?,?,?,?)",
            key,type,period,code,java.sql.Timestamp.from(recordedAt));
        return code;
    }

    private String randomRenewalCode(String period) {
        for(int attempt=0;attempt<128;attempt++) {
            String candidate="GH-"+period+"-"+String.format(Locale.ROOT,"%06d",RANDOM.nextInt(999999)+1);
            // A locking read sees codes committed while waiting for the allocation mutex,
            // including historical sequential codes. The unique DB index remains in place.
            var existing=jdbc.query("select display_code from business_record_codes where display_code=? for update",
                (rs,i)->rs.getString(1),candidate);
            if(existing.isEmpty())return candidate;
        }
        throw ApiExceptions.conflict("Chưa thể cấp mã hồ sơ mới. Vui lòng thử lại.");
    }
}
