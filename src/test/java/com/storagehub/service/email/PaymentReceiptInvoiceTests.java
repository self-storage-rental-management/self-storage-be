package com.storagehub.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.config.JwtProperties;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class PaymentReceiptInvoiceTests {
    private final UUID customer = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private PaymentReceiptEmailQueue queue;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .setName(UUID.randomUUID() + ";MODE=MySQL").addScript("classpath:transactional-email-test-schema.sql").build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE reservations(id BINARY(16) PRIMARY KEY, customer_id BINARY(16))");
        jdbc.execute("CREATE TABLE rentals(id BINARY(16) PRIMARY KEY, customer_id BINARY(16))");
        jdbc.execute("CREATE TABLE renewals(id BINARY(16) PRIMARY KEY, rental_id BINARY(16))");
        jdbc.execute("""
            CREATE TABLE payments(id BINARY(16) PRIMARY KEY, invoice_number VARCHAR(64),
                gateway_intent_id VARCHAR(100), gateway_transaction_no VARCHAR(100),
                reservation_id BINARY(16), renewal_id BINARY(16))
            """);
        queue = new PaymentReceiptEmailQueue(jdbc, new ObjectMapper(), new DataSourceTransactionManager(db), new JwtProperties());
    }

    @AfterEach void close() { db.shutdown(); }

    @Test void receiptReadsTheIssuedNumberAfterCommitAndEnqueuesOncePerPayment() {
        var payload = Map.<String,Object>of("paymentId", payment.toString(), "transactionNo", "BANK-001");
        queue.enqueue(customer, "receipt@example.test", payload);
        jdbc.update("INSERT INTO payments(id, invoice_number) VALUES (?, ?)", bytes(payment), "INV-LEGACY-01");
        queue.enqueue(customer, "receipt@example.test", payload);

        assertThat(deliveredPayload()).containsEntry("transactionNo", "BANK-001").containsEntry("invoiceNumber", "INV-LEGACY-01");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt_emails", Integer.class)).isOne();
    }

    @Test void anUnissuedInvoiceLeavesTheTransactionReferenceAvailable() {
        jdbc.update("INSERT INTO payments(id) VALUES (?)", bytes(payment));
        queue.enqueue(customer, "receipt@example.test", Map.of("paymentId", payment.toString(), "transactionNo", "BANK-001"));
        assertThat(deliveredPayload()).containsEntry("transactionNo", "BANK-001").containsEntry("invoiceNumber", null);
    }

    @Test void legacyRenewalReceiptResolvesItsOwnCustomersPayment() {
        UUID rental = UUID.randomUUID(), renewal = UUID.randomUUID();
        jdbc.update("INSERT INTO rentals VALUES (?, ?)", bytes(rental), bytes(customer));
        jdbc.update("INSERT INTO renewals VALUES (?, ?)", bytes(renewal), bytes(rental));
        jdbc.update("INSERT INTO payments(id, invoice_number, gateway_intent_id, renewal_id) VALUES (?, ?, ?, ?)",
            bytes(payment), "INV-LEGACY-02", "BANK-001", bytes(renewal));
        bookingWithReference(UUID.randomUUID(), "INV-OTHER-CUSTOMER");
        enqueueLegacy();
        assertThat(deliveredPayload()).containsEntry("invoiceNumber", "INV-LEGACY-02");
    }

    @Test void ambiguousLegacyReferenceCannotSelectAnotherInvoice() {
        bookingWithReference(customer, "INV-ONE");
        bookingWithReference(customer, "INV-TWO");
        enqueueLegacy();
        assertThat(deliveredPayload()).containsEntry("invoiceNumber", null);
    }

    @Test void aStoredLegacyNumberDisambiguatesPaymentsWithTheSameBankReference() {
        bookingWithReference(customer, "INV-ONE");
        bookingWithReference(customer, "INV-TWO");
        queue.enqueue(customer, "receipt@example.test", "payment-bill", "Biên lai", "PAYMENT_BILL_EMAIL_SENT", "old-key",
            Map.of("transactionNo", "BANK-001", "invoiceNumber", "INV-TWO"));
        assertThat(deliveredPayload()).containsEntry("invoiceNumber", "INV-TWO");
    }

    private void bookingWithReference(UUID owner, String invoice) {
        UUID booking = UUID.randomUUID();
        jdbc.update("INSERT INTO reservations VALUES (?, ?)", bytes(booking), bytes(owner));
        jdbc.update("INSERT INTO payments(id, invoice_number, gateway_transaction_no, reservation_id) VALUES (?, ?, ?, ?)",
            bytes(UUID.randomUUID()), invoice, "BANK-001", bytes(booking));
    }

    private void enqueueLegacy() {
        queue.enqueue(customer, "receipt@example.test", "payment-bill", "Biên lai", "PAYMENT_BILL_EMAIL_SENT", "old-key",
            Map.of("transactionNo", "BANK-001", "invoiceNumber", "INV-A1B2C3D4"));
    }

    private Map<String,Object> deliveredPayload() {
        var sender = mock(TransactionalEmailService.class);
        var delivered = new HashMap<String,Object>();
        when(sender.deliverEmail(eq(customer), eq("receipt@example.test"), anyString(), eq("payment-bill"),
            anyMap(), eq("PAYMENT_BILL_EMAIL_SENT"))).thenAnswer(call -> {
                delivered.putAll(call.getArgument(4));
                return true;
            });
        queue.deliverPending(sender);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM payment_receipt_emails", String.class)).isEqualTo("SENT");
        return delivered;
    }

    private static byte[] bytes(UUID id) {
        return HexFormat.of().parseHex(id.toString().replace("-", ""));
    }
}
