package com.storagehub.service.ledger;

import com.storagehub.time.BusinessTime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.*;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The accepted quote and the shared ledger are the only sources of money and dates. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled"},havingValue="true")
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RenewalInstallmentService {
    public record Installment(UUID id,int sequence,LocalDate periodStart,LocalDate periodEndExclusive,
        Instant dueAt,BigDecimal amount,BigDecimal paidAmount,BigDecimal outstandingAmount,String status){}
    public record Schedule(UUID renewalId,RenewalPaymentPlan paymentPlan,long ledgerRevision,List<Installment> installments){}
    public record Quote(UUID id,UUID renewalId,UUID acceptedRevisionId,long ledgerRevision,BigDecimal amount,
        String currency,Instant expiresAt,Map<UUID,BigDecimal> allocations){}
    private record Context(Renewal renewal,RenewalWorkflow workflow,RenewalQuoteResponse.Terms terms){}
    private final EntityManager em;
    private final LedgerStore ledger;
    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DuongResourceAccess access;
    private final PaymentProvenance provenance;
    private final ManualRentalPaymentService manualPayments;
    private final NotificationService notifications;

    private Context context(ActorPrincipal actor,UUID id,String audience,boolean write){
        var renewal=em.find(Renewal.class,id);
        if(renewal==null)throw ApiExceptions.notFound("Không tìm thấy hồ sơ gia hạn.");
        var role=RoleCode.valueOf(audience.toUpperCase(Locale.ROOT));
        if(role==RoleCode.CUSTOMER){access.require(actor,role,null,FacilityScopeLevel.READ);
            if(!renewal.getRental().getCustomer().getId().equals(actor.userId()))throw ApiExceptions.notFound("Không tìm thấy hồ sơ gia hạn.");
        }else access.require(actor,role,renewal.getRental().getFacility().getId(),write?FacilityScopeLevel.OPERATE:FacilityScopeLevel.READ,
            write?new SystemPermission[]{SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_PAYMENTS}:new SystemPermission[]{SystemPermission.VIEW_RENTALS});
        if(write){em.lock(renewal.getRental(),LockModeType.PESSIMISTIC_WRITE);em.refresh(renewal.getRental());em.refresh(renewal,LockModeType.PESSIMISTIC_WRITE);}
        var workflow=em.find(RenewalWorkflow.class,id);
        if(workflow==null||workflow.getAcceptedRevision()==null)throw ApiExceptions.conflict("Hồ sơ chưa có điều khoản được chấp nhận.");
        try{return new Context(renewal,workflow,mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ApiExceptions.conflict("Điều khoản đã lưu không hợp lệ.");}
    }
    private LedgerStore.Snapshot account(Context c){
        var a=ledger.read(c.renewal().getRental().getId()).orElseThrow(()->ApiExceptions.conflict("Chưa mở sổ công nợ cho yêu cầu này."));
        if(!a.customerId().equals(c.renewal().getRental().getCustomer().getId())||!a.facilityId().equals(c.renewal().getRental().getFacility().getId()))
            throw ApiExceptions.conflict("Thông tin sổ công nợ không khớp hồ sơ.");
        return a;
    }
    private Map<Integer,List<LedgerStore.Obligation>> obligations(Context c,LedgerStore.Snapshot a){
        var result=new LinkedHashMap<Integer,List<LedgerStore.Obligation>>();
        for(var spec:FinancialContractSpecifications.accepted(c.workflow().getAcceptedRevision(),c.terms())){
            int sequence=spec.dueDate()==null?1:Integer.parseInt(spec.sourceReference().substring(spec.sourceReference().lastIndexOf(':')+1));
            var matches=a.obligations().stream().filter(o->Objects.equals(o.renewalId(),c.renewal().getId())&&o.sourceRef().equals(spec.sourceReference())
                &&o.amount().compareTo(spec.amount())==0&&o.kind()==spec.kind()&&o.dueAt().toEpochMilli()==spec.requireDue(c.workflow().getPaymentDeadline(),c.workflow().getSigningBalanceDueAt()).toEpochMilli()).toList();
            if(matches.size()!=1||!ledger.renewalPaymentCommandMatches(matches.getFirst(),spec,a.customerId()))
                throw ApiExceptions.conflict("Lịch thanh toán chưa có đủ khoản phải thu đã xác thực.");
            result.computeIfAbsent(sequence,ignored->new ArrayList<>()).add(matches.getFirst());
        }
        return result;
    }
    private UUID periodId(Context c,int sequence){return UUID.nameUUIDFromBytes((c.workflow().getAcceptedRevision().getId()+":month:"+sequence).getBytes(StandardCharsets.UTF_8));}
    private Schedule schedule(Context c,LedgerStore.Snapshot a){
        var entries=new ArrayList<Installment>();
        for(var entry:obligations(c,a).entrySet()){
            var amount=entry.getValue().stream().map(LedgerStore.Obligation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            var outstanding=entry.getValue().stream().map(LedgerStore.Obligation::outstanding).reduce(BigDecimal.ZERO,BigDecimal::add);
            var paid=entry.getValue().stream().map(o->o.allocated().subtract(o.refunded())).reduce(BigDecimal.ZERO,BigDecimal::add);
            var due=entry.getValue().stream().map(LedgerStore.Obligation::dueAt).min(Comparator.naturalOrder()).orElseThrow();
            var start=c.terms().extensionStartDate().plusMonths(entry.getKey()-1);
            var end=c.terms().monthly()?c.terms().extensionStartDate().plusMonths(entry.getKey()):c.terms().extensionEndExclusive();
            boolean closed=Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.payment_expired).contains(c.renewal().getStatus());
            String status=closed?"CANCELLED":outstanding.signum()==0?"PAID":!due.isAfter(Instant.now())?"OVERDUE":paid.signum()>0?"PARTIALLY_PAID":"UNPAID";
            entries.add(new Installment(periodId(c,entry.getKey()),entry.getKey(),start,end,due,amount,paid,outstanding,status));
        }
        return new Schedule(c.renewal().getId(),c.terms().paymentPlan(),a.revision(),List.copyOf(entries));
    }
    public Schedule schedule(ActorPrincipal actor,UUID id,String audience){
        var c=context(actor,id,audience,false);
        if(c.workflow().getPaymentDeadline()!=null)return schedule(c,account(c));
        var planned=new RenewalQuoteResponse(null,c.renewal().getRental().getId(),c.renewal().getRental().getCustomer().getId(),c.terms(),null,null).paymentSchedule();
        return new Schedule(id,c.terms().paymentPlan(),0,planned.stream().map(p->new Installment(periodId(c,p.sequence()),p.sequence(),p.periodStart(),p.periodEndExclusive(),
            p.dueDate()==null?null:p.dueDate().atStartOfDay(BusinessTime.ZONE).toInstant(),p.amount(),BigDecimal.ZERO,p.amount(),"PLANNED")).toList());
    }
    private void payable(Context c){
        var rental=c.renewal().getRental();
        if(c.renewal().getStatus()!=RenewalStatus.completed||rental.getStatus()!=RentalStatus.active||rental.getActualReturnedAt()!=null||rental.getCompletedAt()!=null)
            throw ApiExceptions.conflict("Chỉ thanh toán kỳ tiếp theo khi gia hạn đã ký và hồ sơ thuê còn hiệu lực.");
        if(em.createQuery("select count(t) from ReturnCase t where t.rental.id=:id",Long.class).setParameter("id",rental.getId()).getSingleResult()>0
            ||em.createQuery("select count(t) from OverdueFollowUp t where t.rental.id=:id and t.type='RECOVERY_HANDOFF'",Long.class).setParameter("id",rental.getId()).getSingleResult()>0)
            throw ApiExceptions.conflict("Hồ sơ đã chuyển trả kho hoặc thu hồi; cần đối soát trước khi thu thêm.");
        if(em.createQuery("select count(p) from PaymentComplaint p where p.reservation.id=:id and p.status in :statuses",Long.class)
            .setParameter("id",rental.getReservation().getId()).setParameter("statuses",List.of(PaymentComplaintStatus.PENDING,PaymentComplaintStatus.REVIEW_OVERDUE)).getSingleResult()>0)
            throw ApiExceptions.conflict("Hồ sơ có khiếu nại thanh toán chưa giải quyết.");
    }
    private void noPending(UUID id){
        if(payments.findAllByRenewal_IdAndStatusIn(id,List.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED)).stream().anyMatch(p->p.getPurpose()==PaymentType.RENEWAL_INSTALLMENT))
            throw ApiExceptions.conflict("Đang có giao dịch thanh toán kỳ chưa rõ kết quả; vui lòng tiếp tục giao dịch đó.");
    }
    public Quote quote(ActorPrincipal actor,UUID id,String audience,RenewalInstallmentCommands.Quote body){
        var c=context(actor,id,audience,true);payable(c);noPending(id);
        var a=account(c);var schedule=schedule(c,a);var selected=new LinkedHashSet<>(body.installmentIds());
        if(selected.size()!=body.installmentIds().size())throw ApiExceptions.validation("Danh sách kỳ bị trùng.",null);
        var known=new HashSet<UUID>();schedule.installments().forEach(i->known.add(i.id()));
        if(!known.containsAll(selected))throw ApiExceptions.notFound("Không tìm thấy kỳ trong hồ sơ này.");
        // Pay any older unpaid period first, including all due periods before prepayment.
        boolean omitted=false;var allocations=new LinkedHashMap<UUID,BigDecimal>();var grouped=obligations(c,a);
        for(var i:schedule.installments()){
            if(i.outstandingAmount().signum()==0){if(selected.contains(i.id()))throw ApiExceptions.conflict("Kỳ đã được thanh toán đủ.");continue;}
            if(!selected.contains(i.id())){omitted=true;continue;}
            if(omitted)throw ApiExceptions.conflict("Cần thanh toán các kỳ còn thiếu trước khi trả kỳ tiếp theo.");
            for(var o:grouped.get(i.sequence()))if(o.outstanding().signum()>0){if(o.refunded().signum()!=0)throw ApiExceptions.conflict("Kỳ có hoàn tiền cần đối soát.");allocations.put(o.id(),o.outstanding());}
        }
        if(allocations.isEmpty())throw ApiExceptions.conflict("Không có khoản cần thanh toán.");
        // Do not let a different overdue debt be silently bypassed by early payment.
        if(a.obligations().stream().anyMatch(o->o.outstanding().signum()>0&&!o.dueAt().isAfter(Instant.now())&&!allocations.containsKey(o.id())))
            throw ApiExceptions.conflict("Cần giải quyết đầy đủ các khoản đến hạn trước khi trả trước.");
        var quote=new Quote(UUID.randomUUID(),id,c.workflow().getAcceptedRevision().getId(),a.revision(),allocations.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add),"VND",Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),Map.copyOf(allocations));
        jdbc.update("insert into renewal_installment_payment_quotes(id,renewal_id,accepted_revision_id,actor_id,ledger_revision,amount,allocations_json,expires_at_ms) values(?,?,?,?,?,?,?,?)",
            quote.id().toString(),id.toString(),quote.acceptedRevisionId().toString(),actor.userId().toString(),a.revision(),quote.amount(),json(allocations),quote.expiresAt().toEpochMilli());
        return quote;
    }
    private Quote loadQuote(Context c,UUID quoteId,UUID actor,boolean allowExpired){
        var rows=jdbc.queryForList("select * from renewal_installment_payment_quotes where id=? and renewal_id=? for update",quoteId.toString(),c.renewal().getId().toString());
        if(rows.size()!=1)throw ApiExceptions.notFound("Không tìm thấy báo giá thanh toán kỳ.");var row=rows.getFirst();
        if(!actor.toString().equals(row.get("actor_id"))||!c.workflow().getAcceptedRevision().getId().toString().equals(row.get("accepted_revision_id")))
            throw ApiExceptions.notFound("Báo giá không thuộc người thực hiện hoặc điều khoản hiện tại.");
        var expires=Instant.ofEpochMilli(((Number)row.get("expires_at_ms")).longValue());
        if(!allowExpired&&!expires.isAfter(Instant.now()))throw ApiExceptions.conflict("Báo giá thanh toán đã hết hạn; vui lòng lấy báo giá mới.");
        return new Quote(quoteId,c.renewal().getId(),c.workflow().getAcceptedRevision().getId(),((Number)row.get("ledger_revision")).longValue(),new BigDecimal(row.get("amount").toString()),"VND",expires,allocations(row.get("allocations_json").toString()));
    }
    private void requireCurrent(Context c,Quote quote){
        var a=account(c);if(a.revision()!=quote.ledgerRevision())throw ApiExceptions.conflict("Sổ công nợ đã thay đổi; vui lòng lấy báo giá mới.");
        for(var entry:quote.allocations().entrySet())if(a.obligations().stream().noneMatch(o->o.id().equals(entry.getKey())&&Objects.equals(o.renewalId(),c.renewal().getId())&&o.outstanding().compareTo(entry.getValue())==0&&o.refunded().signum()==0))
            throw ApiExceptions.conflict("Kỳ đã được thanh toán hoặc điều chỉnh; vui lòng tải lại.");
    }
    private void key(String key){if(key==null||key.isBlank()||key.length()>100)throw ApiExceptions.validation("Khóa yêu cầu thanh toán không hợp lệ.",null);}
    public Payment prepareIntent(ActorPrincipal actor,UUID id,UUID quoteId,String key){
        key(key);var c=context(actor,id,"customer",true);
        var old=payments.findByIdempotencyKey(key).orElse(null);
        if(old!=null){requireBinding(old,id,quoteId,actor.userId());return old;}
        var existing=jdbc.queryForList("select payment_id from renewal_installment_payment_bindings where quote_id=?",String.class,quoteId.toString());
        if(!existing.isEmpty()){var p=payments.findById(UUID.fromString(existing.getFirst())).orElseThrow();requireBinding(p,id,quoteId,actor.userId());return p;}
        payable(c);noPending(id);var quote=loadQuote(c,quoteId,actor.userId(),false);requireCurrent(c,quote);
        var p=new Payment();p.setInitiatedBy(em.find(User.class,actor.userId()));p.setRenewal(c.renewal());p.setRenewalRevision(c.workflow().getAcceptedRevision());
        p.setPurpose(PaymentType.RENEWAL_INSTALLMENT);p.setStatus(PaymentStatus.PENDING);p.setAmount(quote.amount());p.setCurrency("VND");
        p.setPaymentDeadline(quote.expiresAt());p.setIdempotencyKey(key);p.setGatewayIntentId("SH"+UUID.randomUUID().toString().replace("-",""));p.setGatewayProvider("VNPAY");
        payments.saveAndFlush(p);bind(p,quote);return p;
    }
    private void bind(Payment p,Quote q){jdbc.update("insert into renewal_installment_payment_bindings(payment_id,quote_id,renewal_id) values(?,?,?)",p.getId().toString(),q.id().toString(),q.renewalId().toString());}
    private void requireBinding(Payment p,UUID renewal,UUID quote,UUID actor){
        if(p.getRenewal()==null||!renewal.equals(p.getRenewal().getId())||!actor.equals(p.getInitiatedBy().getId())||p.getPurpose()!=PaymentType.RENEWAL_INSTALLMENT
            ||jdbc.queryForObject("select count(*) from renewal_installment_payment_bindings where payment_id=? and quote_id=? and renewal_id=?",Long.class,p.getId().toString(),quote.toString(),renewal.toString())!=1)
            throw ApiExceptions.conflict("Khóa hoặc báo giá đã được sử dụng cho giao dịch khác.");
    }
    public Payment read(ActorPrincipal actor,UUID id,UUID paymentId,String audience){context(actor,id,audience,false);var p=payments.findById(paymentId).orElseThrow(()->ApiExceptions.notFound("Không tìm thấy giao dịch."));if(p.getRenewal()==null||!id.equals(p.getRenewal().getId()))throw ApiExceptions.notFound("Không tìm thấy giao dịch.");return p;}

    public RenewalVnpaySettlementService.Result settle(Payment p,UUID evidence){
        if(p.getRenewalLedgerReceiptRef()!=null)return new RenewalVnpaySettlementService.Result(true,p.getRenewalLedgerReceiptRef(),null);
        var renewal=em.find(Renewal.class,p.getRenewal().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(renewal.getStatus()!=RenewalStatus.completed||p.getStatus()!=PaymentStatus.PAID||!Objects.equals(evidence,p.getRenewalEvidenceRef())
            ||evidence==null||provenance.classify(p)!=PaymentProvenance.Classification.SANDBOX||p.getRefundedAmount().signum()!=0)
            return new RenewalVnpaySettlementService.Result(false,null,"Giao dịch kỳ chưa đủ bằng chứng đối soát.");
        var workflow=em.find(RenewalWorkflow.class,renewal.getId());
        if(!workflow.getAcceptedRevision().getId().equals(p.getRenewalRevision().getId()))return new RenewalVnpaySettlementService.Result(false,null,"Điều khoản giao dịch không khớp.");
        var rows=jdbc.queryForList("select q.allocations_json,q.amount from renewal_installment_payment_quotes q join renewal_installment_payment_bindings b on b.quote_id=q.id where b.payment_id=? and b.renewal_id=?",p.getId().toString(),renewal.getId().toString());
        if(rows.size()!=1||new BigDecimal(rows.getFirst().get("amount").toString()).compareTo(p.getAmount())!=0)return new RenewalVnpaySettlementService.Result(false,null,"Không tìm thấy phân bổ đã chấp nhận.");
        var amounts=allocations(rows.getFirst().get("allocations_json").toString());var a=ledger.read(renewal.getRental().getId()).orElseThrow();
        for(var entry:amounts.entrySet())if(a.obligations().stream().noneMatch(o->o.id().equals(entry.getKey())&&Objects.equals(o.renewalId(),renewal.getId())&&o.outstanding().compareTo(entry.getValue())>=0&&o.refunded().signum()==0))
            return new RenewalVnpaySettlementService.Result(false,null,"Khoản đã được thanh toán hoặc điều chỉnh; tiền nhận được cần đối soát.");
        // Recheck return/recovery before allocation. Received funds remain durable even if allocation is blocked.
        if(renewal.getRental().getStatus()!=RentalStatus.active||renewal.getRental().getActualReturnedAt()!=null||em.createQuery("select count(t) from ReturnCase t where t.rental.id=:id",Long.class).setParameter("id",renewal.getRental().getId()).getSingleResult()>0
            ||em.createQuery("select count(t) from OverdueFollowUp t where t.rental.id=:id and t.type='RECOVERY_HANDOFF'",Long.class).setParameter("id",renewal.getRental().getId()).getSingleResult()>0)
            return new RenewalVnpaySettlementService.Result(false,null,"Hồ sơ đã trả kho hoặc thu hồi; tiền nhận được cần đối soát.");
        var receipt=ledger.receiveSandbox(a.rentalId(),p.getAmount(),amounts,new LedgerStore.GatewayProof(p.getId(),evidence,p.getGatewayTransactionNo()),renewal.getRental().getCustomer().getId(),"renewal-installment:"+p.getId(),a.revision(),p.getPaidAt());
        p.setRenewalLedgerReceiptRef(receipt);p.setRenewalAllocatedAt(Instant.now());
        event(renewal,p,null);notice(renewal,p.getAmount());return new RenewalVnpaySettlementService.Result(true,receipt,null);
    }
    public UUID receipt(ActorPrincipal actor,UUID id,RenewalInstallmentCommands.Receipt body,String key){
        key(key);var c=context(actor,id,"staff",true);
        var manualKey="RENEWAL-BALANCE-"+id+"-"+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        var old=payments.findByIdempotencyKey(manualKey).orElse(null);
        if(old!=null){var quote=loadQuote(c,body.paymentQuoteId(),actor.userId(),true);
            if(old.getRenewal()==null||!id.equals(old.getRenewal().getId())||!actor.userId().equals(old.getInitiatedBy().getId())||old.getRenewalLedgerReceiptRef()==null||old.getAmount().compareTo(quote.amount())!=0
                ||jdbc.queryForObject("select count(*) from renewal_installment_payment_bindings where payment_id=? and quote_id=?",Long.class,old.getId().toString(),body.paymentQuoteId().toString())!=1
                ||!body.transactionReference().equals(old.getGatewayTransactionNo())||!body.paymentMethod().equals(old.getGatewayBankCode())||!Boolean.TRUE.equals(body.received())||!provenance.manualReceiptMatches(old,body.receiptFileId()))throw ApiExceptions.conflict("Khóa yêu cầu đã dùng với nội dung khác.");
            return old.getId();}
        payable(c);noPending(id);var quote=loadQuote(c,body.paymentQuoteId(),actor.userId(),false);requireCurrent(c,quote);
        if(!Boolean.TRUE.equals(body.received()))throw ApiExceptions.validation("Cần xác nhận đã nhận tiền.",null);
        var file=em.find(FileAsset.class,body.receiptFileId(),LockModeType.PESSIMISTIC_READ);
        if(file==null||file.getStatus()!=FileAssetStatus.ACTIVE||!"DUONG_RENEWAL_INSTALLMENT_RECEIPT".equals(file.getEntityType())||!id.equals(file.getEntityId())
            ||file.getUploadedBy()==null||!actor.userId().equals(file.getUploadedBy().getId()))throw ApiExceptions.validation("Chứng từ chưa được lưu đúng hồ sơ và người thu tiền.",null);
        var recorded=manualPayments.renewalSettlement(c.renewal(),c.workflow().getAcceptedRevision(),em.find(User.class,actor.userId()),quote.amount(),body.paymentMethod(),body.transactionReference(),key,body.receiptFileId(),Instant.now());
        if(!recorded.created())throw ApiExceptions.conflict("Biên nhận đã được sử dụng; vui lòng tải lại giao dịch cũ.");
        var p=recorded.payment();var a=account(c);
        var receipt=ledger.receive(a.rentalId(),"BANK_TRANSFER".equals(body.paymentMethod())?LedgerStore.Method.VERIFIED_BANK:LedgerStore.Method.CASH,quote.amount(),quote.allocations(),
            "renewal-installment-manual:"+id+":"+body.transactionReference(),body.receiptFileId(),actor.userId(),"installment-manual:"+key,a.revision(),p.getPaidAt());
        p.setRenewalLedgerReceiptRef(receipt);p.setRenewalAllocatedAt(Instant.now());bind(p,quote);event(c.renewal(),p,body.receiptFileId());notice(c.renewal(),quote.amount());return p.getId();
    }
    private void event(Renewal n,Payment p,UUID file){var payload=new LinkedHashMap<String,Object>();payload.put("paymentRef",p.getId());payload.put("amount",p.getAmount());payload.put("currency","VND");payload.put("receiptFileId",file);payload.put("receiptId",p.getRenewalLedgerReceiptRef());em.persist(new RenewalOperationEvent(n,"INSTALLMENT_PAYMENT",Instant.now(),p.getInitiatedBy().getId(),json(payload)));}
    private void notice(Renewal n,BigDecimal amount){notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.RENEWAL,"Đã ghi nhận thanh toán kỳ gia hạn","Đã nhận "+amount.toPlainString()+" đồng và phân bổ vào các kỳ thuê đã chọn.",n.getId());}
    /** Read-only writer proof used by the shared partial financial inventory. */
    public boolean receiptMatches(Payment p,LedgerStore.Receipt receipt){
        if(p.getRenewal()==null||p.getRenewalRevision()==null||p.getStatus()!=PaymentStatus.PAID||p.getRenewalLedgerReceiptRef()==null||!receipt.id().equals(p.getRenewalLedgerReceiptRef())
            ||p.getPaidAt()==null||receipt.receivedAt().toEpochMilli()!=p.getPaidAt().toEpochMilli()||receipt.amount().compareTo(p.getAmount())!=0||p.getRefundedAmount().signum()!=0)return false;
        var quotes=jdbc.queryForList("select q.allocations_json,q.amount from renewal_installment_payment_quotes q join renewal_installment_payment_bindings b on b.quote_id=q.id where b.payment_id=? and b.renewal_id=? and q.accepted_revision_id=?",p.getId().toString(),p.getRenewal().getId().toString(),p.getRenewalRevision().getId().toString());
        if(quotes.size()!=1||new BigDecimal(quotes.getFirst().get("amount").toString()).compareTo(p.getAmount())!=0)return false;
        var expected=allocations(quotes.getFirst().get("allocations_json").toString());var actual=new LinkedHashMap<UUID,BigDecimal>();
        for(var row:jdbc.queryForList("select obligation_id,rental_id,amount from rental_ledger_allocations where receipt_id=?",receipt.id().toString())){
            if(!p.getRenewal().getRental().getId().toString().equals(row.get("rental_id")))return false;
            actual.put(UUID.fromString(row.get("obligation_id").toString()),new BigDecimal(row.get("amount").toString()));
        }
        if(!expected.keySet().equals(actual.keySet())||expected.entrySet().stream().anyMatch(e->e.getValue().compareTo(actual.get(e.getKey()))!=0))return false;
        if(receipt.method()==LedgerStore.Method.VNPAY_SANDBOX){
            if(p.getPurpose()!=PaymentType.RENEWAL_INSTALLMENT||provenance.classify(p)!=PaymentProvenance.Classification.SANDBOX||!("payment:"+p.getId()).equals(receipt.evidenceRef()))return false;
            var proof=jdbc.queryForList("select payment_id,proof_log_id,transaction_no,environment from rental_ledger_gateway_evidence where receipt_id=?",receipt.id().toString());
            return proof.size()==1&&p.getId().toString().equals(proof.getFirst().get("payment_id"))&&p.getRenewalEvidenceRef().toString().equals(proof.getFirst().get("proof_log_id"))&&p.getGatewayTransactionNo().equals(proof.getFirst().get("transaction_no"))&&"SANDBOX".equals(proof.getFirst().get("environment"));
        }
        if(!Set.of(LedgerStore.Method.CASH,LedgerStore.Method.VERIFIED_BANK).contains(receipt.method())||!provenance.manualReceiptMatches(p,receipt.evidenceFileId()))return false;
        var file=em.find(FileAsset.class,receipt.evidenceFileId());
        return file!=null&&file.getStatus()==FileAssetStatus.ACTIVE&&"DUONG_RENEWAL_INSTALLMENT_RECEIPT".equals(file.getEntityType())&&p.getRenewal().getId().equals(file.getEntityId())
            &&file.getUploadedBy()!=null&&file.getUploadedBy().getId().equals(p.getInitiatedBy().getId())
            &&(receipt.method()==LedgerStore.Method.CASH?"CASH":"BANK_TRANSFER").equals(p.getGatewayBankCode());
    }
    public List<com.storagehub.service.overdue.OverdueSources.Obligation> verifiedMonthlyObligations(Rental rental,LedgerStore.Snapshot snapshot){
        var result=new ArrayList<com.storagehub.service.overdue.OverdueSources.Obligation>();
        for(var w:em.createQuery("select w from RenewalWorkflow w where w.renewal.rental.id=:id",RenewalWorkflow.class).setParameter("id",rental.getId()).getResultList())try{
            var terms=mapper.readValue(w.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
            if(!terms.monthly()||w.getRenewal().getStatus()!=RenewalStatus.completed)continue;
            var c=new Context(w.getRenewal(),w,terms);
            for(var group:obligations(c,snapshot).values())for(var o:group)result.add(new com.storagehub.service.overdue.OverdueSources.Obligation(o.id(),rental.getId(),o.dueAt(),o.outstanding(),"VND"));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw ApiExceptions.conflict("Điều khoản gia hạn không hợp lệ.");}
        return List.copyOf(result);
    }
    public void publishDueNotices(UUID id){
        var n=em.find(Renewal.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(n==null||n.getStatus()!=RenewalStatus.completed||n.getRental().getStatus()!=RentalStatus.active||n.getRental().getActualReturnedAt()!=null)return;
        var w=em.find(RenewalWorkflow.class,id);if(w==null||w.getAcceptedRevision()==null)return;
        final RenewalQuoteResponse.Terms terms;
        try{terms=mapper.readValue(w.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}catch(Exception invalid){throw ApiExceptions.conflict("Điều khoản gia hạn không hợp lệ.");}
        if(!terms.monthly())return;var c=new Context(n,w,terms);var today=LocalDate.now(BusinessTime.ZONE);
        for(var i:schedule(c,account(c)).installments()){
            if(i.outstandingAmount().signum()<=0)continue;
            var due=i.dueAt().atZone(BusinessTime.ZONE).toLocalDate();if(due.isAfter(today.plusDays(3)))continue;
            String kind=due.isBefore(today)?"OVERDUE":due.equals(today)?"DUE":"DUE_SOON";
            if(jdbc.update("insert ignore into renewal_installment_notices(renewal_id,installment_id,notice_kind) values(?,?,?)",id.toString(),i.id().toString(),kind)==0)continue;
            String title=kind.equals("OVERDUE")?"Kỳ thanh toán gia hạn đã quá hạn":kind.equals("DUE")?"Kỳ thanh toán gia hạn đến hạn hôm nay":"Sắp đến hạn thanh toán kỳ gia hạn";
            String content="Kỳ "+i.sequence()+", hạn "+due+", còn phải trả "+i.outstandingAmount().toPlainString()+" đồng. Vui lòng kiểm tra lịch thanh toán.";
            var recipients=new LinkedHashSet<UUID>();recipients.add(n.getRental().getCustomer().getId());
            if(!kind.equals("DUE_SOON"))for(var user:em.createQuery("select distinct s.user from UserFacilityScope s where s.facility.id=:f",User.class).setParameter("f",n.getRental().getFacility().getId()).getResultList()){
                if(access.scope(user.getId(),n.getRental().getFacility().getId(),FacilityScopeLevel.READ)
                    &&(access.eligible(user,RoleCode.MANAGER,SystemPermission.VIEW_RENTALS)||access.eligible(user,RoleCode.STAFF,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_PAYMENTS)))recipients.add(user.getId());
            }
            for(var user:recipients)notifications.createNotification(user,NotificationType.RENEWAL,title,content,id);
        }
    }
    private Map<UUID,BigDecimal> allocations(String json){try{return mapper.readValue(json,new TypeReference<LinkedHashMap<UUID,BigDecimal>>(){});}catch(Exception e){throw ApiExceptions.conflict("Phân bổ thanh toán đã lưu không hợp lệ.");}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
