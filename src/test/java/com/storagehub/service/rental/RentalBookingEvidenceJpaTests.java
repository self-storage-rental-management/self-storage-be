package com.storagehub.service.rental;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** SQL-backed rollback tests; these fixtures exist only inside the H2 test transaction. */
@DataJpaTest(properties={"storagehub.project.payment-environment=VNPAY_SANDBOX","storagehub.project.sandbox-recognition-enabled=true","storagehub.integration.ledger.enabled=true","storagehub.integration.sandbox-reconciliation.enabled=true"}) @ActiveProfiles("test") @Import({RentalBookingEvidenceReader.class,com.storagehub.service.ledger.SandboxPaymentReconciliationService.class,com.storagehub.service.integration.DuongResourceAccess.class,com.storagehub.service.ledger.LedgerQueryService.class,com.storagehub.service.ledger.LedgerCoverageInventory.class,com.storagehub.service.RentalQueryService.class,com.storagehub.service.PaymentProvenance.class,com.storagehub.service.VnpayService.class,com.storagehub.service.AuditLogService.class,com.storagehub.service.ledger.PaymentReconciliationPreflight.class,com.storagehub.service.ledger.SandboxPaymentRecognitionPolicy.class,RentalBookingEvidenceJpaTests.JsonConfig.class})
class RentalBookingEvidenceJpaTests {
    @org.springframework.boot.test.context.TestConfiguration static class JsonConfig {
        @org.springframework.context.annotation.Bean(destroyMethod="close") java.sql.Connection ledgerTestSchema(javax.sql.DataSource datasource)throws Exception {
            var connection=datasource.getConnection();
            // H2 CHECK optimization retains the DDL session comparator. Keep that TEST session open until context close.
            if(connection.getMetaData().getURL().startsWith("jdbc:h2:"))new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("rental-ledger-test-schema.sql")).populate(connection);
            return connection;
        }
        @org.springframework.context.annotation.Bean com.storagehub.service.ledger.LedgerStore ledger(org.springframework.jdbc.core.JdbcTemplate jdbc,java.sql.Connection ledgerTestSchema) {
            return new com.storagehub.service.ledger.LedgerStore(jdbc);
        }
        @org.springframework.context.annotation.Bean com.fasterxml.jackson.databind.ObjectMapper mapper(){return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();}
        @org.springframework.context.annotation.Bean com.storagehub.config.VnpayProperties gateway(){var p=new com.storagehub.config.VnpayProperties();p.setEnabled(true);p.setTmnCode("TEST_ONLY");p.setHashSecret("test-only-no-external-gateway");return p;}
        @org.springframework.context.annotation.Bean com.storagehub.security.ActorContext actors(){return org.mockito.Mockito.mock(com.storagehub.security.ActorContext.class);}
        @org.springframework.context.annotation.Bean com.storagehub.service.AdminAuthorizationService auth(){return org.mockito.Mockito.mock(com.storagehub.service.AdminAuthorizationService.class);}
        @org.springframework.context.annotation.Bean com.storagehub.service.NotificationService notifications(){return org.mockito.Mockito.mock(com.storagehub.service.NotificationService.class);}
    }
    @Autowired EntityManager em;
    @Autowired RentalBookingEvidenceReader reader;
    @Autowired com.storagehub.service.RentalQueryService rentalReads;
    @Autowired com.storagehub.service.VnpayService gateway;
    @Autowired com.storagehub.config.VnpayProperties gatewayConfig;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource datasource;
    @Autowired com.storagehub.service.ledger.LedgerStore ledger;
    @Autowired com.storagehub.service.ledger.SandboxPaymentReconciliationService reconciliation;
    @Autowired com.storagehub.service.ledger.LedgerQueryService ledgerReads;
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void authenticatedSandboxReceiptAllocatesRentAndReplaysWithCommittedHttpJdbcReadback() throws Exception {
        gatewayConfig.setEnabled(true);gatewayConfig.setTmnCode("TEST_ONLY");gatewayConfig.setHashSecret("test-only-no-external-gateway");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var data=tx.execute(status->{
            var rental=fixture();var snapshot=snapshot(rental);var p=payment(rental,"VNPAY-",0);p.setStatus(PaymentStatus.PENDING);p.setGatewayProvider("VNPAY");p.setPaidAt(null);
            rental.getReservation().setStatus(ReservationStatus.AWAITING_PAYMENT);rental.getReservation().setPaymentExpiresAt(Instant.parse("2026-10-10T02:00:00Z"));
            var permissions=new HashSet<Permission>();for(var grant:List.of(SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS)) {
                var found=em.createQuery("select p from Permission p where p.code=:code",Permission.class).setParameter("code",grant.code()).getResultList();
                var perm=found.isEmpty()?new Permission():found.getFirst();if(found.isEmpty()){perm.setCode(grant.code());perm.setName("TEST");em.persist(perm);}permissions.add(perm);
            }
            var roles=em.createQuery("select r from Role r where r.code=:code",Role.class).setParameter("code",RoleCode.MANAGER).getResultList();
            var role=roles.isEmpty()?new Role():roles.getFirst();if(roles.isEmpty()){role.setCode(RoleCode.MANAGER);role.setName("TEST Manager");role.setPermissions(permissions);em.persist(role);}
            var user=new User();user.setEmail(UUID.randomUUID()+"@test.invalid");user.setFullName("TEST Manager");user.setPasswordHash("TEST-ONLY");user.setRoles(new HashSet<>(Set.of(role)));em.persist(user);
            var scope=new UserFacilityScope();scope.setUser(user);scope.setFacility(rental.getFacility());scope.setScopeLevel(FacilityScopeLevel.MANAGE);em.persist(scope);em.flush();
            ledger.open(rental.getId(),rental.getCustomer().getId(),rental.getFacility().getId());
            var spec=com.storagehub.service.ledger.FinancialContractSpecifications.initial(rental,snapshot).getFirst();
            ledger.charge(rental.getId(),null,com.storagehub.service.ledger.LedgerStore.Kind.RENT,spec.amount(),NOW,spec.sourceReference(),user.getId(),"TEST-ONLY-charge",0);
            return List.of(rental.getId(),p.getId(),user.getId(),rental.getFacility().getId());
        });
        var params=new TreeMap<String,String>();params.put("vnp_TxnRef",tx.execute(s->em.find(Payment.class,data.get(1)).getGatewayIntentId()));
        params.put("vnp_TmnCode",gatewayConfig.getTmnCode());params.put("vnp_Amount","2000");params.put("vnp_ResponseCode","00");params.put("vnp_TransactionStatus","00");
        params.put("vnp_TransactionNo","TEST-ONLY-RECEIPT-"+UUID.randomUUID());params.put("vnp_PayDate","20261010080000");
        var encoded=params.entrySet().stream().map(e->java.net.URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElseThrow();
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");mac.init(new javax.crypto.spec.SecretKeySpec(gatewayConfig.getHashSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));params.put("vnp_SecureHash",HexFormat.of().formatHex(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(gateway.processCallback(params).rspCode()).isEqualTo("00");
        var actor=new ActorPrincipal(data.get(2),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.VIEW_PAYMENTS.code(),SystemPermission.MANAGE_PAYMENTS.code()),Map.of(data.get(3),FacilityScopeLevel.MANAGE));
        var context=org.mockito.Mockito.mock(com.storagehub.security.ActorContext.class);org.mockito.Mockito.when(context.required()).thenReturn(actor);
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.storagehub.api.rental.SandboxPaymentReconciliationController(context,reconciliation),new com.storagehub.api.rental.RentalLedgerController(context,ledgerReads))
            .setControllerAdvice(new com.storagehub.common.api.GlobalExceptionHandler()).build();
        String path="/api/manager/rentals/"+data.get(0)+"/sandbox-payments/"+data.get(1)+"/reconciliation";
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String receipt=null;
        for(int i=0;i<2;i++) {
            var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","TEST-ONLY-reconcile").contentType("application/json").content("{\"expectedRevision\":1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            var id=mapper.readTree(result.getContentAsString()).path("data").path("receiptId").asText();if(receipt!=null)assertThat(id).isEqualTo(receipt);receipt=id;
        }
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","different-key").contentType("application/json").content("{\"expectedRevision\":2}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        var read=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/manager/rentals/"+data.get(0)+"/ledger"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var view=mapper.readTree(read.getContentAsString()).path("data");assertThat(view.path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(view.path("receipts").get(0).path("method").asText()).isEqualTo("VNPAY_SANDBOX");
        assertThat(view.path("obligations").get(0).path("outstanding").decimalValue()).isEqualByComparingTo("80");
        assertThat(view.path("sandboxReconciliationEnabled").asBoolean()).isTrue();
        assertThat(view.path("coverageInventory").path("payments").get(0).path("linkedReceipt").asBoolean()).isTrue();
        var simulated=tx.execute(status->payment(em.find(Rental.class,data.get(0)),"SIMULATED-",9).getId());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/manager/rentals/"+data.get(0)+"/sandbox-payments/"+simulated+"/reconciliation")
            .header("Idempotency-Key","reject-simulation").contentType("application/json").content("{\"expectedRevision\":2}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        org.mockito.Mockito.when(context.required()).thenReturn(new ActorPrincipal(data.get(2),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","forbidden").contentType("application/json").content("{\"expectedRevision\":2}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        try(var connection=datasource.getConnection();var sql=connection.prepareStatement("select p.method,p.evidence_file_id,g.payment_id from rental_ledger_receipts p join rental_ledger_gateway_evidence g on g.receipt_id=p.id where p.rental_id=?")) {
            sql.setString(1,data.get(0).toString());try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("VNPAY_SANDBOX");assertThat(rows.getString(2)).isNull();assertThat(rows.getString(3)).isEqualTo(data.get(1).toString());assertThat(rows.next()).isFalse();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedHttpReplayPreservesAcceptedAndLateMetadata(boolean late) throws Exception {
        gatewayConfig.setEnabled(true);gatewayConfig.setTmnCode("TEST_ONLY");gatewayConfig.setHashSecret("test-only-no-external-gateway");
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var ids = tx.execute(status -> {
            var r = fixture(); snapshot(r); var p = payment(r, "VNPAY-", 0);
            p.setStatus(PaymentStatus.PENDING); p.setGatewayProvider("VNPAY"); p.setPaidAt(null);
            r.getReservation().setStatus(late ? ReservationStatus.PAYMENT_GRACE : ReservationStatus.AWAITING_PAYMENT);
            r.getReservation().setPaymentExpiresAt(Instant.parse("2026-10-10T02:00:00Z")); em.flush();
            return List.of(p.getId(), r.getId());
        });
        String intent = tx.execute(status -> em.find(Payment.class, ids.getFirst()).getGatewayIntentId());
        var query = new TreeMap<String, String>();
        query.put("vnp_TxnRef", intent); query.put("vnp_TmnCode", gatewayConfig.getTmnCode()); query.put("vnp_Amount", "2000");
        query.put("vnp_ResponseCode", "00"); query.put("vnp_TransactionStatus", "00");
        query.put("vnp_TransactionNo", "TEST_ONLY_TRANSACTION"); query.put("vnp_PayDate", late ? "20261010100000" : "20261010080000");
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.storagehub.api.payment.VnpayPublicController(gateway, gatewayConfig)).build();
        var mutations = List.of(Map.<String, String>of(), Map.of("vnp_TransactionNo", "CONFLICT"),
            Map.of("vnp_TransactionStatus", "02"), Map.of("vnp_ResponseCode", "24"),
            Map.of("vnp_PayDate", "20261010110000"), Map.of("vnp_BankCode", "NEW_BANK"), Map.of("vnp_CardType", "NEW_CARD"));
        for (int i = 0; i <= mutations.size(); i++) {
            var signed = new TreeMap<>(query); if (i > 0) signed.putAll(mutations.get(i - 1));
            signTestCallback(signed);
            var params = new org.springframework.util.LinkedMultiValueMap<String, String>(); signed.forEach(params::add);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/payments/vnpay/ipn").params(params))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value(i == 0 ? "00" : "02"));
        }
        var invalid = new TreeMap<>(query); invalid.put("vnp_SecureHash", "invalid");
        var invalidParams = new org.springframework.util.LinkedMultiValueMap<String, String>(); invalid.forEach(invalidParams::add);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/payments/vnpay/ipn").params(invalidParams))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("97"));
        tx.executeWithoutResult(status -> {
            em.clear(); var p = em.find(Payment.class, ids.getFirst()); var r = em.find(Rental.class, ids.get(1));
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(p.getGatewayTransactionNo()).isEqualTo("TEST_ONLY_TRANSACTION");
            assertThat(p.getGatewayPayDate()).isEqualTo(query.get("vnp_PayDate"));
            assertThat(p.getGatewayResponseCode()).isEqualTo("00"); assertThat(p.getGatewayTransactionStatus()).isEqualTo("00");
            assertThat(p.getGatewayBankCode()).isNull(); assertThat(p.getGatewayCardType()).isNull();
            assertThat(p.getPaidAt()).isEqualTo(Instant.parse(late ? "2026-10-10T03:00:00Z" : "2026-10-10T01:00:00Z"));
            assertThat(p.getFailureCode()).isEqualTo(late ? "PAYMENT_AFTER_DEADLINE" : null);
            assertThat(p.getReservation().getStatus()).isEqualTo(late ? ReservationStatus.EXPIRED : ReservationStatus.CONFIRMED);
            var evidence = reader.read(customer(r), r, false).orElseThrow();
            assertThat(evidence.payments().getFirst().verification()).isEqualTo(late ? "UNVERIFIED" : "SANDBOX");
            assertThat(evidence.payments().getFirst().projectRecognition().eligibleForReconciliation()).isEqualTo(!late);
            assertThat(evidence.payments().getFirst().reconciliation().status()).isEqualTo(late ? "NON_VERIFIED_MONEY" : "LEDGER_ACCOUNT_MISSING");
            assertThat(em.createQuery("select count(a) from ActivityLog a where a.action=:action and a.entityId=:payment", Long.class)
                .setParameter("action", com.storagehub.service.PaymentProvenance.CALLBACK_ACTION).setParameter("payment", p.getId()).getSingleResult())
                .isEqualTo(late ? 0 : 1);
        });
        var context=org.mockito.Mockito.mock(com.storagehub.security.ActorContext.class);
        org.mockito.Mockito.when(context.required()).thenReturn(tx.execute(status->customer(em.find(Rental.class,ids.get(1)))));
        var rentalHttp=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.storagehub.api.rental.CustomerRentalReadController(context,rentalReads)).build();
        var readback=rentalHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/customer/rental-records/"+ids.get(1)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(readback.getContentAsString()).path("data");
        assertThat(data.path("bookingEvidence").path("payments").get(0).path("projectRecognition").path("eligibleForReconciliation").asBoolean()).isEqualTo(!late);
        assertThat(data.path("financialSummary").path("completeness").asText()).isEqualTo("UNKNOWN");
        // Separate JDBC connection observes committed production-service writes, outside the JPA transaction.
        try (var c = datasource.getConnection(); var s = c.prepareStatement(
                "select gateway_transaction_no,gateway_pay_date,gateway_response_code,gateway_transaction_status,failure_code from payments where gateway_intent_id=?")) {
            s.setString(1, intent); try (var rows = s.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("TEST_ONLY_TRANSACTION");
                assertThat(rows.getString(2)).isEqualTo(query.get("vnp_PayDate")); assertThat(rows.getString(3)).isEqualTo("00");
                assertThat(rows.getString(4)).isEqualTo("00"); assertThat(rows.getString(5)).isEqualTo(late ? "PAYMENT_AFTER_DEADLINE" : null);
                assertThat(rows.next()).isFalse();
            }
        }
    }
    private void signTestCallback(Map<String, String> query) throws Exception {
        var encoded = query.entrySet().stream().map(e -> java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)
            + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElseThrow();
        var mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(gatewayConfig.getHashSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA512"));
        query.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void authenticatedCallbackPersistenceAndReplayPreserveSandboxProvenance() throws Exception {
        gatewayConfig.setEnabled(true);gatewayConfig.setTmnCode("TEST_ONLY");gatewayConfig.setHashSecret("test-only-no-external-gateway");
        var r=fixture();snapshot(r);var p=payment(r,"VNPAY-",0);p.setStatus(PaymentStatus.PENDING);p.setGatewayProvider("VNPAY");em.flush();
        p.setPaidAt(null);r.getReservation().setStatus(ReservationStatus.AWAITING_PAYMENT);
        r.getReservation().setPaymentExpiresAt(Instant.parse("2026-10-10T02:00:00Z"));
        var query=new TreeMap<String,String>();query.put("vnp_TxnRef",p.getGatewayIntentId());query.put("vnp_TmnCode",gatewayConfig.getTmnCode());query.put("vnp_Amount","2000");query.put("vnp_ResponseCode","00");query.put("vnp_TransactionStatus","00");query.put("vnp_TransactionNo","TEST_ONLY_TRANSACTION");
        query.put("vnp_PayDate","20261010080000");
        var encoded=query.entrySet().stream().map(e->java.net.URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElseThrow();
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");mac.init(new javax.crypto.spec.SecretKeySpec(gatewayConfig.getHashSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));query.put("vnp_SecureHash",HexFormat.of().formatHex(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.storagehub.api.payment.VnpayPublicController(gateway,gatewayConfig)).build();
        var params=new org.springframework.util.LinkedMultiValueMap<String,String>();query.forEach(params::add);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/payments/vnpay/ipn").params(params))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/payments/vnpay/ipn").params(params))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("02"));
        em.flush();em.clear();
        var rental=em.find(Rental.class,r.getId());var evidence=reader.read(customer(rental),rental,false).orElseThrow();
        assertThat(evidence.payments().getFirst().verification()).isEqualTo("SANDBOX");assertThat(evidence.payments().getFirst().reconciliation().status()).isEqualTo("LEDGER_ACCOUNT_MISSING");
        assertThat(em.createQuery("select count(a) from ActivityLog a where a.action=:action and a.entityId=:payment",Long.class).setParameter("action",com.storagehub.service.PaymentProvenance.CALLBACK_ACTION).setParameter("payment",p.getId()).getSingleResult()).isEqualTo(1);
    }
    private static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
    private ActorPrincipal customer(Rental r) {
        return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
    }
    private ActorPrincipal manager(Rental r,boolean payments,boolean scope) {
        return new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),
            payments?Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.VIEW_PAYMENTS.code()):Set.of(SystemPermission.VIEW_RENTALS.code()),
            scope?Map.of(r.getFacility().getId(),FacilityScopeLevel.READ):Map.of());
    }
    @Test void readsAgreedAmountsAndSimulationWithoutClaimingRealReceiptOrUpdatingRental() {
        var r=fixture();snapshot(r);payment(r,"SIMULATED-",0);em.flush();em.clear();
        r=em.find(Rental.class,r.getId());var updated=r.getUpdatedAt();
        var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.rentalId()).isEqualTo(r.getId());assertThat(evidence.reservationId()).isEqualTo(r.getReservation().getId());
        assertThat(evidence.agreedAmounts().securityDepositAmount()).isEqualByComparingTo("10");
        assertThat(evidence.payments()).hasSize(1);assertThat(evidence.payments().getFirst().verification()).isEqualTo("SIMULATED");
        assertThat(evidence.payments().getFirst().status()).isEqualTo("PAID");
        em.flush();em.clear();assertThat(em.find(Rental.class,r.getId()).getUpdatedAt()).isEqualTo(updated);
    }
    @Test void noSnapshotOrPaymentsMeansAbsentEvidenceNotZeroMoney() {
        var r=fixture();var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.agreedAmounts()).isNull();assertThat(evidence.payments()).isEmpty();assertThat(evidence.paymentsTruncated()).isFalse();
    }
    @Test void paidWithNonSimulationReferenceStillDoesNotProveRealReceipt() {
        var r=fixture();payment(r,"UNVERIFIED-",0);em.flush();
        assertThat(reader.read(customer(r),r,false).orElseThrow().payments().getFirst().verification()).isEqualTo("UNVERIFIED");
    }
    @Test void legacyGatewayMarkerDoesNotProveRealOrSimulatedPaymentAndIsNotExposed() {
        var r=fixture();payment(r,"LEGACY-UNVERIFIED-",0);em.flush();
        var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.payments().getFirst().verification()).isEqualTo("UNVERIFIED");
        assertThat(evidence.payments().getFirst().status()).isEqualTo("PAID");
        assertThat(evidence.payments().getFirst().toString()).doesNotContain("LEGACY-UNVERIFIED-");
    }
    @Test void rentalReadPermissionAloneDoesNotExposePaymentsAndForeignScopeIsDenied() {
        var r=fixture();snapshot(r);payment(r,"SIMULATED-",0);em.flush();
        assertThat(reader.read(manager(r,false,true),r,true)).isEmpty();
        assertThat(reader.read(manager(r,true,true),r,true).orElseThrow().payments()).hasSize(1);
        var rental=r;assertThatThrownBy(()->reader.read(manager(rental,true,false),rental,true)).hasMessageContaining("not found");
    }
    @Test void foreignCustomerAndWrongRoleAreDenied() {
        var r=fixture();var foreign=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        assertThatThrownBy(()->reader.read(foreign,r,false)).hasMessageContaining("not found");
        var staff=new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
        assertThatThrownBy(()->reader.read(staff,r,false)).hasMessageContaining("not found");
        assertThatThrownBy(()->reader.read(null,r,false)).hasMessageContaining("Authentication required");
    }
    @Test void inconsistentSnapshotFailsInsteadOfInventingFinancialCompleteness() {
        var r=fixture();var snapshot=snapshot(r);snapshot.setDueAtCheckIn(BigDecimal.ZERO);em.flush();
        assertThatThrownBy(()->reader.read(customer(r),r,false)).hasMessageContaining("totals are inconsistent");
    }
    @Test void historyIsBoundedAndTruncationExplicit() {
        var r=fixture();for(int i=0;i<21;i++)payment(r,"SIMULATED-",i);em.flush();
        var evidence=reader.read(customer(r),r,false).orElseThrow();assertThat(evidence.payments()).hasSize(20);
        assertThat(evidence.paymentsTruncated()).isTrue();
    }
    private Payment payment(Rental r,String prefix,int i) {
        var p=new Payment();p.setInitiatedBy(r.getCustomer());p.setReservation(r.getReservation());p.setAmount(new BigDecimal("20"));
        p.setCurrency("VND");p.setPurpose(PaymentType.RESERVATION_DEPOSIT);p.setStatus(PaymentStatus.PAID);
        p.setIdempotencyKey("TEST-"+UUID.randomUUID());p.setGatewayIntentId(prefix+UUID.randomUUID());p.setProcessedAt(NOW);em.persist(p);return p;
    }
    private ReservationPricingSnapshot snapshot(Rental r) {
        var s=new ReservationPricingSnapshot();s.setReservation(r.getReservation());s.setPricingPackageCode("TEST");s.setPricingPolicyVersion("TEST");
        s.setRentalMonths(1);s.setMonthlyPrice(new BigDecimal("100"));s.setGrossRentalAmount(new BigDecimal("100"));s.setNetRentalAmount(new BigDecimal("100"));
        s.setReservationDepositAmount(new BigDecimal("20"));s.setSecurityDepositAmount(new BigDecimal("10"));s.setRemainingRentalAmount(new BigDecimal("80"));
        s.setDueAtCheckIn(new BigDecimal("90"));s.setTotalInitialObligation(new BigDecimal("110"));s.setQuotedAt(NOW);s.setExpiresAt(NOW.plusSeconds(1800));em.persist(s);return s;
    }
    private Rental fixture() {
        String key=UUID.randomUUID().toString().substring(0,8);
        var f=new Facility();f.setCode("TEST-"+key);f.setName("TEST");f.setAddress("TEST");f.setCity("TEST");em.persist(f);
        var u=new User();u.setEmail(key+"@test.invalid");u.setFullName("TEST");u.setPasswordHash("TEST-ONLY");em.persist(u);
        var t=new UnitType();t.setFacility(f);t.setCode("S");t.setName("TEST");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);
        t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(BigDecimal.ONE);t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);
        t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        var unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-"+key);unit.setStatus(StorageUnitStatus.occupied);em.persist(unit);
        var q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");
        q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(BigDecimal.ONE);
        q.setSubtotal(BigDecimal.ONE);q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(BigDecimal.ONE);
        q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(BigDecimal.ONE);
        q.setDueAtCheckIn(BigDecimal.ONE);q.setTotalInitialObligation(BigDecimal.ONE);q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        var res=new Reservation();res.setReservationCode("TEST-"+key);res.setIdempotencyKey("TEST-"+key);res.setCustomer(u);res.setSourceQuote(q);
        res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());res.setStatus(ReservationStatus.COMPLETED);em.persist(res);
        var r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());
        r.setContractEndDate(q.getEndDate());r.setMonthlyPrice(BigDecimal.ONE);em.persist(r);em.flush();return r;
    }
}
