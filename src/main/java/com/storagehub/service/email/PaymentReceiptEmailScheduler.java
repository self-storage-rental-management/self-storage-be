package com.storagehub.service.email;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentReceiptEmailScheduler {
    private final PaymentReceiptEmailQueue queue;
    private final TransactionalEmailService sender;
    private final java.util.concurrent.atomic.AtomicBoolean dispatching = new java.util.concurrent.atomic.AtomicBoolean();

    @org.springframework.scheduling.annotation.Async
    @Scheduled(fixedDelayString = "${app.mail.receipt-retry-delay-ms:5000}")
    public void dispatch() {
        if (!dispatching.compareAndSet(false, true)) return;
        try { queue.deliverPending(sender); }
        finally { dispatching.set(false); }
    }
}
