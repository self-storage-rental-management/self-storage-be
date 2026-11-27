package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "money_receipt_codes")
@IdClass(MoneyReceiptCode.MoneyReceiptCodeId.class)
public class MoneyReceiptCode {

    @Id
    @Column(name = "source_type", length = 16, nullable = false)
    private String sourceType;

    @Id
    @Column(name = "source_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID sourceId;

    @Column(name = "receipt_code", length = 17, nullable = false, unique = true)
    private String receiptCode;

    public MoneyReceiptCode() {}

    public MoneyReceiptCode(String sourceType, UUID sourceId, String receiptCode) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.receiptCode = receiptCode;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public String getReceiptCode() {
        return receiptCode;
    }

    public void setReceiptCode(String receiptCode) {
        this.receiptCode = receiptCode;
    }

    public static class MoneyReceiptCodeId implements Serializable {
        private String sourceType;
        private UUID sourceId;

        public MoneyReceiptCodeId() {}

        public MoneyReceiptCodeId(String sourceType, UUID sourceId) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MoneyReceiptCodeId that = (MoneyReceiptCodeId) o;
            return Objects.equals(sourceType, that.sourceType) && Objects.equals(sourceId, that.sourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId);
        }
    }
}
