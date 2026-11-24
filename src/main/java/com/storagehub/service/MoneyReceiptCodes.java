package com.storagehub.service;

import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MoneyReceiptCodes {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public MoneyReceiptCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String find(String source, UUID id) {
        var codes = jdbc.queryForList("SELECT receipt_code FROM money_receipt_codes WHERE source_type = ? AND source_id = UUID_TO_BIN(?)",
            String.class, source, id.toString());
        return codes.isEmpty() ? null : codes.get(0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(String source, UUID id, boolean refund) {
        for (int attempt = 0; attempt < 8; attempt++) {
            var code = new StringBuilder(refund ? "PC" : "PT");
            for (int i = 0; i < 12; i++) {
                if (i % 4 == 0) code.append('-');
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            jdbc.update("""
                INSERT INTO money_receipt_codes(source_type, source_id, receipt_code)
                VALUES (?, UUID_TO_BIN(?), ?)
                ON DUPLICATE KEY UPDATE receipt_code = money_receipt_codes.receipt_code
                """, source, id.toString(), code.toString());
            var issued = jdbc.queryForList("""
                SELECT receipt_code FROM money_receipt_codes
                WHERE source_type = ? AND source_id = UUID_TO_BIN(?) FOR UPDATE
                """, String.class, source, id.toString());
            if (!issued.isEmpty()) return issued.get(0);
        }
        throw new IllegalStateException("Could not allocate a unique receipt code");
    }
}
