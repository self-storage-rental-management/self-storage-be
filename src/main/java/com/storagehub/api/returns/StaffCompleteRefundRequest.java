package com.storagehub.api.returns;

import jakarta.validation.constraints.Size;

public record StaffCompleteRefundRequest(
    @Size(max = 120, message = "transactionReference cannot exceed 120 characters")
    String transactionReference,

    @Size(max = 1000, message = "notes cannot exceed 1000 characters")
    String notes,
    java.util.UUID receiptFileId,
    String paymentMethod,
    java.util.UUID originalPaymentId,
    Boolean confirmed,
    java.math.BigDecimal amount
) {
    public StaffCompleteRefundRequest(String transactionReference,String notes) {this(transactionReference,notes,null,"BANK_TRANSFER",null,null,null);}
    public StaffCompleteRefundRequest(String transactionReference,String notes,java.util.UUID receiptFileId,String paymentMethod) {
        this(transactionReference,notes,receiptFileId,paymentMethod,null,null,null);
    }
    public StaffCompleteRefundRequest(String transactionReference,String notes,java.util.UUID receiptFileId,String paymentMethod,Boolean confirmed,java.math.BigDecimal amount) {
        this(transactionReference,notes,receiptFileId,paymentMethod,null,confirmed,amount);
    }
}
