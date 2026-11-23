package com.storagehub.api.checkin;

import java.time.Instant;

public record ReceiptReminderResponse(
    boolean sent,
    Instant sentAt,
    Instant receiptConfirmationDueAt,
    boolean receiptConfirmationOverdue
) {}
