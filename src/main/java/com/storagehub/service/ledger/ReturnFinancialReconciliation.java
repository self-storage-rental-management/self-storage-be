package com.storagehub.service.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.SetupEarlyExitPolicy;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Binds a return to real check-in funds and exact Ledger debts. Does not invent historical payments. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class ReturnFinancialReconciliation {
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final LedgerStore ledger;
    private final PaymentProvenance provenance;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RenewalOverdueFeeService> overdueFees;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.overdue.RecoverySettlementGuard> recoveryGuards;
    private record Security(Payment payment,BigDecimal amount,ReservationPricingSnapshot pricing) {}
    public record View(BigDecimal deposit,BigDecimal debt,BigDecimal postedOverdueFee,long ledgerRevision,BigDecimal unpostedOverdueFee) {}

    private Security security(Rental rental) {
        if(em.createQuery("select count(c) from PaymentComplaint c where c.reservation.id=:id and c.status in :statuses",Long.class)
            .setParameter("id",rental.getReservation().getId()).setParameter("statuses",List.of(PaymentComplaintStatus.PENDING,PaymentComplaintStatus.REVIEW_OVERDUE)).getSingleResult()>0)
            throw ApiExceptions.conflict("Cần xử lý khiếu nại thanh toán trước khi quyết toán cọc.");
        var snapshots=em.createQuery("select s from ReservationPricingSnapshot s where s.reservation.id=:id",ReservationPricingSnapshot.class)
            .setParameter("id",rental.getReservation().getId()).getResultList();
        if(snapshots.size()!=1)throw unknownDeposit();
        var pricing=snapshots.getFirst();
        if(pricing.getSecurityDepositAmount()==null||pricing.getSecurityDepositAmount().signum()<0
            ||pricing.getSecurityDepositAmount().stripTrailingZeros().scale()>0
            ||pricing.getRemainingRentalAmount()==null||pricing.getDueAtCheckIn()==null
            ||pricing.getRemainingRentalAmount().add(pricing.getSecurityDepositAmount()).compareTo(pricing.getDueAtCheckIn())!=0)throw unknownDeposit();
        var payments=em.createQuery("select p from Payment p where p.reservation.id=:id and p.renewal is null and p.status=:paid",Payment.class)
            .setParameter("id",rental.getReservation().getId()).setParameter("paid",PaymentStatus.PAID)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        Payment checkIn=null;BigDecimal paid=BigDecimal.ZERO;
        for(var p:payments) {
            if(!Set.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.RENTAL_BALANCE).contains(p.getPurpose()))throw unknownDeposit();
            if(!Set.of(PaymentProvenance.Classification.VERIFIED,PaymentProvenance.Classification.SANDBOX).contains(provenance.classify(p))
                ||p.getAmount()==null||p.getPaidAt()==null||p.getPaidAt().isAfter(Instant.now())||p.getRefundedAmount()==null||p.getRefundedAmount().signum()!=0)throw unknownDeposit();
            paid=paid.add(p.getAmount());
            if(p.getPurpose()==PaymentType.RENTAL_BALANCE) {
                if(checkIn!=null||p.getAmount().compareTo(pricing.getDueAtCheckIn())!=0)throw unknownDeposit();
                checkIn=p;
            }
        }
        if(checkIn==null||pricing.getNetRentalAmount()==null
            ||paid.compareTo(pricing.getNetRentalAmount().add(pricing.getSecurityDepositAmount()))!=0)throw unknownDeposit();
        BigDecimal released=jdbc.queryForObject("select coalesce(sum(deposit_amount),0) from return_financial_snapshots where rental_id=? and settled_at_ms is not null",BigDecimal.class,rental.getId().toString());
        if(released.signum()!=0)throw ApiExceptions.conflict("Cọc bảo đảm đã được quyết toán; không thể sử dụng lần nữa.");
        Long historical=em.createQuery("select count(r) from ReturnCase r where r.rental.id=:id and r.status=:status",Long.class)
            .setParameter("id",rental.getId()).setParameter("status",ReturnCaseStatus.completed).getSingleResult();
        if(historical>0)throw ApiExceptions.conflict("Hồ sơ có quyết toán cũ cần đối soát cọc trước khi xử lý tiếp.");
        return new Security(checkIn,pricing.getSecurityDepositAmount(),pricing);
    }
    private RuntimeException unknownDeposit() {
        return ApiExceptions.conflict("Chưa xác minh được cọc thực thu từ chứng từ nhận kho. Cần đối soát; không suy ra cọc từ giá thuê.");
    }
    public BigDecimal deposit(Rental rental) {return security(rental).amount();}

    public View capture(ReturnCase rc) {
        return capture(rc,false);
    }
    public View captureRecovery(ReturnCase rc) {
        return capture(rc,true);
    }
    private View capture(ReturnCase rc,boolean recovery) {
        var rental=em.find(Rental.class,rc.getRental().getId(),LockModeType.PESSIMISTIC_WRITE);
        var security=security(rental);ledger.open(rental.getId(),rental.getCustomer().getId(),rental.getFacility().getId());
        if (!recovery) SetupEarlyExitPolicy.capture(rc, security.pricing());
        var account=ledger.read(rental.getId()).orElseThrow();ledger.lockForSettlement(rental.getId(),account.revision());
        if(!account.customerId().equals(rental.getCustomer().getId())||!account.facilityId().equals(rental.getFacility().getId()))throw ApiExceptions.conflict("Sổ công nợ không khớp hồ sơ thuê.");
        Instant calculatedAt=rc.getInspectedAt()==null?Instant.now():rc.getInspectedAt();
        RenewalOverdueFeeService.Quote feeQuote=null;
        if(recovery) {
            var source=overdueFees==null?null:overdueFees.getIfAvailable();
            if(source==null)throw ApiExceptions.conflict("Chưa có nguồn tính phí quá hạn, không thể quyết toán thu hồi.");
            feeQuote=source.quote(rental,account,calculatedAt);
        }
        var allocations=new LinkedHashMap<UUID,BigDecimal>();BigDecimal debt=BigDecimal.ZERO,postedFee=BigDecimal.ZERO;
        for(var o:account.obligations()) {
            if(o.outstanding().signum()==0)continue;
            if(o.refunded().signum()>0)throw ApiExceptions.conflict("Khoản công nợ có hoàn tiền cần được đối soát trước khi đưa vào quyết toán.");
            boolean fee=o.sourceRef().startsWith("rental-overdue:"+rental.getId()+":");
            if(recovery&&fee) {
                if(feeQuote.posted().stream().noneMatch(posted->posted.id().equals(o.id())))
                    throw ApiExceptions.conflict("Phí quá hạn đã ghi sổ không khớp kỳ thuê hiện tại, cần đối soát.");
                allocations.put(o.id(),o.outstanding());debt=debt.add(o.outstanding());postedFee=postedFee.add(o.outstanding());
                continue;
            }
            if(o.renewalId()==null) {
                // Check-in payments above prove these initial funds; do not charge them a second time.
                String prefix="reservation-snapshot:"+security.pricing().getId()+":";
                BigDecimal expected=o.kind()==LedgerStore.Kind.RENT?security.pricing().getNetRentalAmount():o.kind()==LedgerStore.Kind.SECURITY_DEPOSIT?security.amount():null;
                if(expected==null||!o.sourceRef().equals(prefix+o.kind().name())||o.amount().compareTo(expected)!=0)throw ApiExceptions.conflict("Công nợ ban đầu chưa khớp chứng từ nhận kho.");
                allocations.put(o.id(),o.outstanding());continue;
            }
            var renewal=em.find(Renewal.class,o.renewalId());
            if(renewal==null||!renewal.getRental().getId().equals(rental.getId()))throw ApiExceptions.conflict("Nguồn công nợ gia hạn không hợp lệ.");
            if(renewal.getStatus()!=RenewalStatus.completed)throw ApiExceptions.conflict("Cần đối soát hoặc đóng yêu cầu gia hạn chưa hoàn tất trước khi quyết toán trả kho.");
            if(o.dueAt().isAfter(Instant.now()))continue; // Future monthly termination policy is intentionally unchanged.
            var workflow=em.find(RenewalWorkflow.class,renewal.getId());
            if(!fee) {
                if(workflow==null||workflow.getAcceptedRevision()==null)throw ApiExceptions.conflict("Công nợ gia hạn thiếu điều khoản đã chấp nhận, cần đối soát.");
                try {
                    var terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
                    var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
                    if(specs.stream().noneMatch(s->s.sourceReference().equals(o.sourceRef())&&s.kind()==o.kind()&&s.amount().compareTo(o.amount())==0
                        &&ledger.renewalPaymentCommandMatches(o,s,rental.getCustomer().getId())))throw ApiExceptions.conflict("Nguồn công nợ gia hạn cần được đối soát.");
                }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw ApiExceptions.conflict("Điều khoản công nợ không hợp lệ.");}
            }
            if(o.kind()==LedgerStore.Kind.SECURITY_DEPOSIT)throw ApiExceptions.conflict("Nguồn cọc gia hạn không hợp lệ.");
            allocations.put(o.id(),o.outstanding());debt=debt.add(o.outstanding());if(fee)postedFee=postedFee.add(o.outstanding());
        }
        var old=jdbc.queryForList("select revision,settled_at_ms from return_financial_snapshots where return_id=? for update",rc.getId().toString());
        if(!old.isEmpty()&&old.getFirst().get("settled_at_ms")!=null)throw ApiExceptions.conflict("Quyết toán đã được ghi nhận.");
        long revision=old.isEmpty()?1:((Number)old.getFirst().get("revision")).longValue()+1;
        if(!old.isEmpty())jdbc.update("insert into return_financial_snapshot_history(return_id,ledger_revision,security_payment_id,deposit_amount,debt_amount,allocations_json,revision,captured_at_ms,setup_statement_json) select return_id,ledger_revision,security_payment_id,deposit_amount,debt_amount,allocations_json,revision,captured_at_ms,setup_statement_json from return_financial_snapshots where return_id=?",rc.getId().toString());
        if(old.isEmpty())jdbc.update("insert into return_financial_snapshots(return_id,rental_id,ledger_revision,security_payment_id,deposit_amount,debt_amount,allocations_json,revision,captured_at_ms) values(?,?,?,?,?,?,?,?,?)",
            rc.getId().toString(),rental.getId().toString(),account.revision(),security.payment().getId().toString(),security.amount(),debt,json(allocations),revision,Instant.now().toEpochMilli());
        else jdbc.update("update return_financial_snapshots set ledger_revision=?,security_payment_id=?,deposit_amount=?,debt_amount=?,allocations_json=?,revision=?,captured_at_ms=? where return_id=?",
            account.revision(),security.payment().getId().toString(),security.amount(),debt,json(allocations),revision,Instant.now().toEpochMilli(),rc.getId().toString());
        jdbc.update("update return_financial_snapshots set setup_statement_json=? where return_id=?",
            rc.isSetupExitRequested() ? setupStatement(rc) : null, rc.getId().toString());
        if(feeQuote!=null) {
            jdbc.update("insert into recovery_overdue_fee_assessments(return_id,snapshot_revision,overdue_days,accrued_amount,outstanding_amount,unposted_amount,source_ref,calculated_at_ms) values(?,?,?,?,?,?,?,?)",
                rc.getId().toString(),revision,feeQuote.overdueDays(),feeQuote.accrued(),feeQuote.outstanding(),feeQuote.unposted(),feeQuote.sourceRef(),calculatedAt.toEpochMilli());
            rc.setOverdueDays(Math.toIntExact(feeQuote.overdueDays()));
        }
        rc.setDepositAmount(security.amount());return new View(security.amount(),debt,postedFee,account.revision(),feeQuote==null?BigDecimal.ZERO:feeQuote.unposted());
    }

    public void requireCurrent(ReturnCase rc) {
        var rows=jdbc.queryForList("select * from return_financial_snapshots where return_id=? for update",rc.getId().toString());
        if(rows.size()!=1)throw ApiExceptions.conflict("Quyết toán cần được kiểm kê và đối soát lại theo sổ công nợ.");
        var row=rows.getFirst();if(row.get("settled_at_ms")!=null)throw ApiExceptions.conflict("Quyết toán đã hoàn tất.");
        var recovery=recoveryGuards==null?null:recoveryGuards.getIfAvailable();
        if(recovery!=null&&recovery.linked(rc)) {
            var assessments=jdbc.queryForList("select unposted_amount from recovery_overdue_fee_assessments where return_id=? and snapshot_revision=?",rc.getId().toString(),row.get("revision"));
            if(assessments.size()!=1||new BigDecimal(assessments.getFirst().get("unposted_amount").toString()).compareTo(rc.getOverdueFee())!=0)
                throw ApiExceptions.conflict("Cần đối soát lại phí quá hạn trước khi xác nhận hoặc thu hoàn tiền.");
        }
        var account=ledger.read(rc.getRental().getId()).orElseThrow();
        ledger.lockForSettlement(account.rentalId(),((Number)row.get("ledger_revision")).longValue());
        var proof=security(rc.getRental());
        if (rc.isSetupExitRequested()) {
            if (!Objects.equals(row.get("setup_statement_json"), setupStatement(rc)))
                throw ApiExceptions.conflict("Bảng kê trả kho trong setup đã thay đổi; cần đối soát lại.");
            if (rc.isSetupEarlyExit() && (!SetupEarlyExitPolicy.applies(rc)
                || !Objects.equals(proof.pricing().getSetupEarlyExitPolicyVersion(), rc.getSetupEarlyExitPolicyVersion())
                || proof.pricing().getSetupEarlyExitFee().compareTo(rc.getEarlyTerminationFee()) != 0
                || proof.pricing().getNetRentalAmount().compareTo(rc.getRentalRefundCredit()) != 0))
                throw ApiExceptions.conflict("Điều khoản hoặc tiền thuê thực thu không khớp bảng kê setup.");
        }
        if(!proof.payment().getId().toString().equals(row.get("security_payment_id"))
            ||proof.amount().compareTo(rc.getDepositAmount())!=0||new BigDecimal(row.get("debt_amount").toString()).compareTo(rc.getOutstandingFee())!=0)
            throw ApiExceptions.conflict("Cọc hoặc công nợ đã thay đổi; cần đối soát lại quyết toán.");
    }
    public boolean recordedReceiptMatches(ReturnCase rc,String method,String reference,UUID file) {
        return rc.getStatus()==ReturnCaseStatus.completed&&method!=null&&reference!=null&&file!=null
            &&jdbc.queryForObject("select count(*) from return_settlement_receipts r join return_financial_snapshots f on f.return_id=r.return_id where r.return_id=? and r.payment_method=? and r.transaction_reference=? and r.receipt_file_id=? and f.settled_at_ms is not null",Long.class,
                rc.getId().toString(),method,reference,file.toString())==1;
    }

    public com.storagehub.api.returns.ReturnCaseResponse.SettlementReceipt settledReceipt(ReturnCase rc, boolean recovery) {
        if (rc.getSettlementPaidAt() == null || rc.getSettlementPaymentId() == null) {
            if(rc.getStatus()!=ReturnCaseStatus.completed||rc.getTotalDeductions().signum()<=0
                ||rc.getAmountDueFromCustomer().signum()!=0||rc.getNetRefundAmount().signum()!=0)return null;
            var offsets=jdbc.query("select settlement_reference,settled_at_ms from return_financial_snapshots where return_id=? and settlement_reference=? and settled_at_ms is not null",
                (row,index)->new com.storagehub.api.returns.ReturnCaseResponse.SettlementReceipt("OFFSET",rc.getTotalDeductions(),"DEPOSIT_OFFSET",row.getString(1),null,Instant.ofEpochMilli(row.getLong(2))),
                rc.getId().toString(),"OFFSET:"+rc.getId());
            return offsets.size()==1?offsets.getFirst():null;
        }
        String sql = recovery
            ? "select direction,amount,payment_method,transaction_reference,receipt_file_id,recorded_at from recovery_settlement_receipts where return_case_id=? and transaction_reference=?"
            : "select direction,amount,payment_method,transaction_reference,receipt_file_id,recorded_at_ms from return_settlement_receipts where return_id=? and transaction_reference=?";
        Object returnId = recovery
            ? java.nio.ByteBuffer.allocate(16).putLong(rc.getId().getMostSignificantBits()).putLong(rc.getId().getLeastSignificantBits()).array()
            : rc.getId().toString();
        var receipts = jdbc.query(sql, (row, index) -> {
            var fileBytes=recovery?java.nio.ByteBuffer.wrap(row.getBytes("receipt_file_id")):null;
            UUID fileId=recovery?new UUID(fileBytes.getLong(),fileBytes.getLong()):UUID.fromString(row.getString("receipt_file_id"));
            return new com.storagehub.api.returns.ReturnCaseResponse.SettlementReceipt(
                row.getString("direction"),row.getBigDecimal("amount"),row.getString("payment_method"),row.getString("transaction_reference"),fileId,
                recovery?row.getTimestamp("recorded_at").toInstant():Instant.ofEpochMilli(row.getLong("recorded_at_ms")));
        }, returnId, rc.getSettlementPaymentId());
        return receipts.size() == 1 ? receipts.getFirst() : null;
    }

    public com.storagehub.api.returns.ReturnCaseResponse.OverdueSettlement overdueSettlement(ReturnCase rc,boolean recovery) {
        if(!recovery||rc.getInspectedAt()==null)return null;
        var rows=jdbc.query("select a.accrued_amount,a.outstanding_amount,a.overdue_days,a.calculated_at_ms from recovery_overdue_fee_assessments a join return_financial_snapshots f on f.return_id=a.return_id and f.revision=a.snapshot_revision where a.return_id=?",
            (row,index)->new com.storagehub.api.returns.ReturnCaseResponse.OverdueSettlement(row.getBigDecimal(1),row.getBigDecimal(2),row.getLong(3),Instant.ofEpochMilli(row.getLong(4)),false),rc.getId().toString());
        return rows.size()==1?rows.getFirst():new com.storagehub.api.returns.ReturnCaseResponse.OverdueSettlement(null,null,null,null,true);
    }

    public void receipt(ActorPrincipal actor,ReturnCase rc,String method,String reference,UUID file) {
        requireCurrent(rc);
        if(!Set.of("CASH","BANK_TRANSFER").contains(method==null?"":method)||reference==null||reference.isBlank()||reference.length()>120||file==null)
            throw ApiExceptions.validation("Cần phương thức thu/hoàn, mã giao dịch và biên lai đã lưu.",null);
        var asset=em.find(FileAsset.class,file,LockModeType.PESSIMISTIC_READ);
        if(asset==null||asset.getStatus()!=FileAssetStatus.ACTIVE||!"RETURN_CASE".equals(asset.getEntityType())
            ||!rc.getId().equals(asset.getEntityId())||asset.getUploadedBy()==null||!actor.userId().equals(asset.getUploadedBy().getId()))
            throw ApiExceptions.validation("Biên lai phải thuộc hồ sơ trả kho và do nhân viên đang xử lý tải lên.",null);
        if (asset.getStorageKey() == null || asset.getChecksumSha256() == null || asset.getSizeBytes() < 1
            || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(asset.getStorageKey())))
            throw ApiExceptions.conflict("Không tìm thấy tệp biên lai thực tế. Chưa thể xác nhận thu hoặc hoàn tiền.");
        String direction=rc.getStatus()==ReturnCaseStatus.payment_due?"COLLECTION":rc.getStatus()==ReturnCaseStatus.refund_pending?"REFUND":null;
        if(direction==null)throw ApiExceptions.conflict("Hồ sơ không có khoản cần thu hoặc hoàn.");
        var amount=direction.equals("COLLECTION")?rc.getAmountDueFromCustomer():rc.getNetRefundAmount();
        try{jdbc.update("insert into return_settlement_receipts(return_id,direction,amount,payment_method,transaction_reference,receipt_file_id,recorded_by,recorded_at_ms) values(?,?,?,?,?,?,?,?)",
            rc.getId().toString(),direction,amount,method,reference,file.toString(),actor.userId().toString(),Instant.now().toEpochMilli());}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw ApiExceptions.conflict("Biên lai hoặc giao dịch đã được sử dụng.");}
    }

    /** External transfers must already have a bound Staff receipt (ordinary return or Recovery). */
    public void settle(ReturnCase rc,UUID actor,String reference,boolean transferVerified) {
        requireCurrent(rc);
        if((rc.getAmountDueFromCustomer().signum()>0||rc.getNetRefundAmount().signum()>0)&&!transferVerified)
            throw ApiExceptions.conflict("Cần xác nhận thu hoặc hoàn tiền bằng chứng từ trước khi hoàn tất.");
        var row=jdbc.queryForList("select * from return_financial_snapshots where return_id=?",rc.getId().toString()).getFirst();
        final Map<UUID,BigDecimal> allocations;
        try{allocations=mapper.readValue(row.get("allocations_json").toString(),new TypeReference<LinkedHashMap<UUID,BigDecimal>>(){});}
        catch(Exception invalid){throw ApiExceptions.conflict("Bản đối soát công nợ không hợp lệ.");}
        var account=ledger.read(rc.getRental().getId()).orElseThrow();
        for(var entry:allocations.entrySet()) {
            var o=account.obligations().stream().filter(v->v.id().equals(entry.getKey())).findFirst().orElseThrow();
            if(o.outstanding().compareTo(entry.getValue())!=0)throw ApiExceptions.conflict("Khoản nợ đã thay đổi; cần đối soát lại.");
            jdbc.update("insert into return_ledger_allocations(return_id,obligation_id,rental_id,amount) values(?,?,?,?)",rc.getId().toString(),entry.getKey().toString(),rc.getRental().getId().toString(),entry.getValue());
        }
        jdbc.update("update return_financial_snapshots set settled_at_ms=?,settlement_reference=?,recorded_by=? where return_id=? and settled_at_ms is null",
            Instant.now().toEpochMilli(),reference,actor==null?null:actor.toString(),rc.getId().toString());
        ledger.returnSettled(rc.getRental().getId());
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}

    private String setupStatement(ReturnCase rc) {
        var statement = new LinkedHashMap<String, Object>();
        statement.put("policy", rc.getSetupEarlyExitPolicyVersion());
        statement.put("returnedAt", rc.getSetupReturnedAt() == null ? null
            : rc.getSetupReturnedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        statement.put("eligible", rc.isSetupEarlyExit());
        statement.put("rentalCredit", rc.getRentalRefundCredit() == null ? null : rc.getRentalRefundCredit().stripTrailingZeros().toPlainString());
        statement.put("terminationFee", rc.getEarlyTerminationFee() == null ? null : rc.getEarlyTerminationFee().stripTrailingZeros().toPlainString());
        return json(statement);
    }
}
