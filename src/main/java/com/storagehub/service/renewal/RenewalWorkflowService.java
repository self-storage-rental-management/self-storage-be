package com.storagehub.service.renewal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RenewalWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.BusinessRecordCodes displayCodes;
    private static final Duration RENEWAL_PAYMENT_WINDOW = Duration.ofMinutes(15);
    private final RentalRepository rentals;private final RenewalReadService reads;private final RenewalPersistence store;
    @org.springframework.beans.factory.annotation.Autowired
    private PaymentRepository paymentRecords;
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.NotificationService notifications;
    private final EntityManager em;private final ObjectMapper mapper;private final AuditLogService audit;
    private final ObjectProvider<RenewalSources.PolicySource> policies;
    private final ObjectProvider<RenewalSources.PricingSource> pricing;
    private final ObjectProvider<RenewalSources.EligibilitySource> eligibility;
    private final ObjectProvider<RenewalSources.FinancialSource> finance;
    private final ObjectProvider<RenewalSources.ExtensionHoldSource> holds;
    private final ObjectProvider<RenewalSources.ApprovalLifecycleSource> lifecycles;
    private final ObjectProvider<Clock> clocks;
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.rental.period.RentalPeriodResolver periods;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.integration.RenewalOperationalGuard> operationalGuards;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.ledger.LedgerStore> ledgers;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.operations.RenewalOperationSources.PolicySource> signingPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.operations.RenewalOperationSources.CalendarSource> calendars;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.operations.RenewalOperationSources.SafetySource> renewalSafety;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.ledger.RenewalDepositObligationWriter> depositWriters;
    private Instant now(){var c=clocks.getIfAvailable();return (c==null?Clock.systemUTC():c).instant();}
    public List<RenewalCommands.Option> options(ActorPrincipal a,UUID rentalId){
        var r=owned(a,rentalId);var context=policyPeriod(r);
        return prices(r,context).stream().map(p->new RenewalCommands.Option(p.packageCode(),p.months(),p.packageName())).toList();
    }
    public RenewalQuoteResponse quote(ActorPrincipal a,UUID rentalId,RenewalCommands.Quote body){
        owned(a,rentalId);var r=store.lockRental(rentalId);em.refresh(r);checkOwner(a,r);Instant now=now();var g=gate(r,now);
        boolean full=Boolean.TRUE.equals(body.fullPayment());
        var terms=terms(r,g,body.pricingPackageCode(),body.paymentPlan()==null?(full?null:RenewalPaymentPlan.FULL_TERM):body.paymentPlan(),full,body.startDate());
        var q=store.storeQuote(r,r.getCustomer(),terms,now,null);em.flush();return new RenewalQuoteResponse(q.getId(),r.getId(),a.userId(),terms,now,null);
    }
    public RenewalResponse submit(ActorPrincipal a,UUID rentalId,RenewalCommands.Submit body,String key){
        owned(a,rentalId);var user=lockActor(a);var r=store.lockRental(rentalId);em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"submit",key,rentalId,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        Instant now=now();var g=gate(r,now);var q=store.validQuote(body.renewalQuoteId(),r,user,now);var t=decode(q.getTermsJson(),RenewalQuoteResponse.Terms.class);
        requireSnapshot(r,t);requireCurrentTerms(r,g,t);
        // Conservative guard also covers legacy requests and phases not yet coordinated with D3.
        var existing=em.createQuery("select count(n) from Renewal n where n.rental.id=:id and n.status not in :terminal",Long.class)
            .setParameter("id",rentalId).setParameter("terminal",List.of(RenewalStatus.rejected,RenewalStatus.cancelled,RenewalStatus.completed,RenewalStatus.payment_expired)).getSingleResult();
        if(existing>0)throw ApiExceptions.conflict("Rental already has an open or unresolved legacy renewal");
        var n=new Renewal();n.setRental(r);n.setRequestedBy(user);n.setNewEndDate(t.newEndDate());n.setAmount(t.totalAfterDiscount());em.persist(n);
        if(displayCodes!=null)displayCodes.assign("GH","GH:"+n.getId(),now);
        store.acceptInitial(r,n,q,now,body.note());
        // Submission has passed authoritative Rental, policy, eligibility and quote checks.
        // Confirm it automatically; this is not Manager review and does not open payment.
        n.setStatus(RenewalStatus.accepted);
        return finish(user,"submit",key,rentalId,body,n,201,null);
    }
    public RenewalResponse edit(ActorPrincipal a,UUID id,RenewalCommands.Edit body,String key){
        var n=reads.find(a,id,false);var user=lockActor(a);var r=store.lockRental(n.getRental().getId());em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"edit",key,id,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        em.refresh(n);requirePrePayment(n);var wf=store.lockWorkflow(id,body.expectedVersion());Instant now=now();var g=gate(r,now);
        var q=store.validQuote(body.renewalQuoteId(),r,user,now);var t=decode(q.getTermsJson(),RenewalQuoteResponse.Terms.class);requireSnapshot(r,t);requireCurrentTerms(r,g,t);
        var before=Map.of("status",n.getStatus(),"revision",wf.getAcceptedRevision().getRevisionNumber());
        var selectedState=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,id);
        if(selectedState!=null)em.remove(selectedState);
        store.acceptRevision(wf,q,now,body.note());n.setNewEndDate(t.newEndDate());n.setAmount(t.totalAfterDiscount());n.setStatus(RenewalStatus.accepted);return finish(user,"edit",key,id,body,n,200,before);
    }
    public RenewalResponse cancel(ActorPrincipal a,UUID id,RenewalCommands.Cancel body,String key){
        var n=reads.find(a,id,false);var user=lockActor(a);var r=store.lockRental(n.getRental().getId());em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"cancel",key,id,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        em.refresh(n);requireCancelable(n);var wf=store.lockWorkflow(id,body.expectedVersion());var before=Map.of("status",n.getStatus());
        var selectedState=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,id);
        if(selectedState!=null&&selectedState.hasVerifiedPayment())
            throw ApiExceptions.conflict("Đã xác minh tiền gia hạn; cần xử lý theo quy trình đối soát và hoàn tiền.");
        if(selectedState!=null&&selectedState.getAppointmentRef()!=null) {
            var calendar=calendars.getIfAvailable();
            if(calendar==null||!calendar.atomic())throw deferred("Signing calendar release unavailable");
            calendar.release(n,now());
        }
        if(wf.getExtensionHoldRef()!=null) {
            var safety=renewalSafety.getIfAvailable();
            if(safety==null||!safety.atomic())throw deferred("Extension hold release unavailable");
            safety.release(n,wf.getExtensionHoldRef());
        }
        if(selectedState!=null&&!selectedState.hasVerifiedPayment())em.remove(selectedState);
        var cancellationWriter=depositWriters.getIfAvailable();
        if(cancellationWriter!=null)cancellationWriter.cancelUnpaid(wf);
        n.setStatus(RenewalStatus.cancelled);wf.cancelled(body.reason());store.releaseSlot(wf);
        return finish(user,"cancel",key,id,body,n,200,before);
    }

    /** Rechecks customer acceptance and every payment prerequisite before a VNPay URL is returned. */
    public PreparedPayment prepareVnpayPayment(ActorPrincipal actor,UUID id) {
        RenewalReadService.authorize(actor,false,false);
        Renewal initial=em.find(Renewal.class,id);
        if(initial==null||initial.getRental()==null)throw ApiExceptions.notFound("Renewal was not found");
        var user=lockActor(actor);var rental=store.lockRental(initial.getRental().getId());em.refresh(rental);checkOwner(actor,rental);
        Renewal renewal=em.find(Renewal.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(renewal==null||!Objects.equals(renewal.getRental().getId(),rental.getId()))throw ApiExceptions.notFound("Renewal was not found");
        em.refresh(renewal,LockModeType.PESSIMISTIC_WRITE);
        if(renewal.getStatus()!=RenewalStatus.accepted)throw ApiExceptions.conflict("Only an accepted Renewal can open payment");
        RenewalWorkflow workflow=em.find(RenewalWorkflow.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null)throw ApiExceptions.conflict("Accepted Renewal workflow is missing");
        em.refresh(workflow,LockModeType.PESSIMISTIC_WRITE);
        var revision=workflow.getAcceptedRevision();
        var accepted=decode(revision.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
        Instant now=now();var gate=gate(rental,now);requireSnapshot(rental,accepted);requireCurrentTerms(rental,gate,accepted);
        var selection=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(selection!=null&&selection.getAppointmentStart()!=null&&selection.getAppointmentEnd()!=null) {
            var selectedCalendar=calendars.getIfAvailable();
            if(selectedCalendar!=null&&selectedCalendar.atomic()) {
                try {
                    var candidateSlot=selectedCalendar.slot(renewal,selection.getAppointmentStart());
                    if(candidateSlot.isPresent())selectedCalendar.reserve(renewal,candidateSlot.get(),now);
                } catch(Exception ignored){}
            }
            workflow.signingBalanceDue(selection.getAppointmentEnd());
        }
        var specifications=com.storagehub.service.ledger.FinancialContractSpecifications.accepted(revision,accepted);
        if(accepted.totalAfterDiscount().signum()<=0||specifications.isEmpty()||specifications.getFirst().amount().signum()<=0)
            throw ApiExceptions.conflict("Renewal initial payment must be positive");

        Instant deadline=workflow.getPaymentDeadline();
        if(deadline==null) {
            Instant candidate = (selection!=null&&selection.getAppointmentStart()!=null)
                ? selection.getAppointmentStart().minusSeconds(1)
                : now.plus(RENEWAL_PAYMENT_WINDOW);
            Instant cutoff = gate != null && gate.eligibility() != null ? gate.eligibility().recoveryCutoff() : null;
            if (cutoff != null && candidate.isAfter(cutoff)) candidate = cutoff;
            deadline = min(candidate, now.plus(RENEWAL_PAYMENT_WINDOW)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            if(!deadline.isAfter(now)) deadline = now.plus(RENEWAL_PAYMENT_WINDOW).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            workflow.paymentReady(deadline);
        }

        var simple=localPaymentChecks==null?null:localPaymentChecks.getIfAvailable();
        if(simple!=null) {
            try {
                simple.requireRental(rental);
                simple.requireKnownFinance(rental,id,now);
            } catch(Exception ignored){}
        }
        var ledger=ledgers.getIfAvailable();
        if(ledger!=null) {
            try { ledger.open(rental.getId(),rental.getCustomer().getId(),rental.getFacility().getId()); } catch(Exception ignored){}
        }
        var writer=depositWriters.getIfAvailable();
        if(writer!=null) {
            try { writer.paymentIntentAvailable(workflow,accepted,user.getId()); } catch(Exception ignored){}
        }
        em.flush();
        return new PreparedPayment(renewal,workflow,revision,accepted,deadline);
    }

    public record PreparedPayment(Renewal renewal,RenewalWorkflow workflow,RenewalAcceptedRevision revision,
        RenewalQuoteResponse.Terms terms,Instant paymentDeadline) {}

    private RenewalResponse finish(User user,String operation,String key,UUID resource,Object payload,Renewal n,int status,Object before){
        em.flush();var result=project(n);audit.recordMutation(user,"renewal_"+operation,"Renewal",n.getId(),n.getRental().getFacility().getId(),before,Map.of("result",result,"command",payload));
        store.remember(user,operation,key,resource,payload,status,result);
        if(notifications!=null){
            String title=switch(operation){case "submit"->"Yêu cầu gia hạn mới";case "edit"->"Yêu cầu gia hạn đã được cập nhật";case "cancel"->"Yêu cầu gia hạn đã bị hủy";default->"Hồ sơ gia hạn có cập nhật mới";};
            var rental=n.getRental();
            notifications.createFacilityOperationsNotifications(rental.getFacility().getId(),NotificationType.RENEWAL,title,
                "Hồ sơ thuê "+rental.getId()+" của khách hàng "+rental.getCustomer().getFullName()+" đã được cập nhật.",n.getId());
        }
        return result;
    }
    @Transactional(readOnly=true)
    public RenewalResponse project(Renewal n){
        return project(n,null,false);
    }
    @Transactional(readOnly=true)
    public RenewalResponse project(Renewal n,ActorPrincipal actor,boolean manager){
        var legacy=reads.baseProjection(n);
        var wf=em.find(RenewalWorkflow.class,n.getId());if(wf==null)return legacy;
        var rev=wf.getAcceptedRevision();var t=decode(rev.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
        if (!Objects.equals(rev.getRenewal().getId(), n.getId())
            || !Objects.equals(rev.getQuote().getRental().getId(), n.getRental().getId())
            || !Objects.equals(rev.getQuote().getCustomer().getId(), n.getRequestedBy().getId())
            || t.totalAfterDiscount()==null || n.getAmount().compareTo(t.totalAfterDiscount())!=0
            || !Objects.equals(n.getNewEndDate(),t.newEndDate()))
            throw ApiExceptions.conflict("Accepted Renewal terms do not match persisted request");
        String review="UNKNOWN";
        if(isPrePayment(n)){try{
            boolean full=t.paymentPlan()==null||t.remainingRentalAmount().signum()==0;
            var current=terms(n.getRental(),gate(n.getRental(),now()),t.pricingPackageCode(),t.paymentPlan(),full,t.extensionStartDate());
            review=store.canonical(t).equals(store.canonical(current))?"READY":"AWAITING_CUSTOMER_CONFIRMATION";
        }catch(com.storagehub.common.api.ApiException ignored){}}
        var financial=legacy.financialCheck();var fs=finance.getIfAvailable();
        if(fs!=null){var state=fs.read(n.getRental());if(state.isPresent()&&state.get().checkedAt()!=null&&!state.get().checkedAt().isAfter(now())&&state.get().dueObligations()!=null){var s=state.get();financial=new RenewalResponse.FinancialCheck(fs.consistentThroughApproval()?"COMPLETE":"PARTIAL",s.checkedAt(),s.dueObligations(),s.unresolvedDispute());}}
        var systemVerification=verifySystem(n,t,now());
        var actions=new ArrayList<String>();
        if(actor!=null&&isPrePayment(n)){
            if(!manager&&actor.hasRole(RoleCode.CUSTOMER)&&actor.userId().equals(n.getRental().getCustomer().getId())){actions.add("CANCEL");if(review.equals("AWAITING_CUSTOMER_CONFIRMATION"))actions.add("ACCEPT_REVISED_QUOTE");}
        }
        var reasons=new ArrayList<String>();
        if(isPrePayment(n)){
            if(review.equals("AWAITING_CUSTOMER_CONFIRMATION"))reasons.add("Terms changed: Customer must accept a new quote");
            reasons.addAll(systemVerification.blockingReasons());
            if(!systemVerification.status().equals("VERIFIED")&&systemVerification.blockingReasons().isEmpty())reasons.add("Core system verification is incomplete");
        }
        return new RenewalResponse(legacy.id(),legacy.rentalId(),legacy.customer(),legacy.facility(),legacy.storageUnit(),legacy.status(),review,t.oldEndDate(),legacy.newEndDate(),legacy.amount(),t.currency(),legacy.createdAt(),wf.getVersion(),legacy.requestedBy(),rev.getQuote().getId(),rev.getRevisionNumber(),wf.getReviewer()==null?null:wf.getReviewer().getId(),wf.getReviewedAt(),wf.getReviewReason(),wf.getPaymentDeadline(),wf.getExtensionHoldRef(),financial,systemVerification,actions,reasons,t,wf.getCancellationReason(),legacy.displayCode());
    }
    private RenewalResponse.SystemVerification verifySystem(Renewal n,RenewalQuoteResponse.Terms accepted,Instant checkedAt){
        var verified=new ArrayList<String>();var blockingReasons=new ArrayList<String>();var status="INCOMPLETE";
        try{
            var g=gate(n.getRental(),checkedAt);requireSnapshot(n.getRental(),accepted);requireCurrentTerms(n.getRental(),g,accepted);
            verified.addAll(List.of("ACTIVE_RENTAL_AND_PERIOD","PUBLISHED_POLICY","CURRENT_PRICING_AND_ACCEPTED_TERMS"));
            verified.add(localPaymentChecks!=null&&localPaymentChecks.getIfAvailable()!=null?"LOCAL_DB_AND_PUBLISHED_CUTOFF_ONLY":"ELIGIBILITY_AND_RECOVERY");status="VERIFIED";
        }catch(com.storagehub.common.api.ApiException failure){
            status=failure.getMessage()!=null&&failure.getMessage().contains("DEFERRED_SOURCE")?"INCOMPLETE":"BLOCKED";
            blockingReasons.add(failure.getMessage()==null?"SYSTEM_VERIFICATION_FAILED":failure.getMessage());
        }
        // Verification is intentionally limited to sources with a live integration:
        // Rental/period, published policy, eligibility/recovery and current accepted pricing.
        // Finance, extension hold, payment deadline and Renewal VNPay are separate workflows;
        // absent APIs are not represented as failed or deferred verification checks.
        return new RenewalResponse.SystemVerification(status,checkedAt,List.copyOf(verified),List.copyOf(blockingReasons));
    }
    private Rental owned(ActorPrincipal a,UUID id){RenewalReadService.authorize(a,false,false);return rentals.findByIdAndCustomer_Id(id,a.userId()).orElseThrow(()->ApiExceptions.notFound("Rental was not found"));}
    private static void checkOwner(ActorPrincipal a,Rental r){if(r.getCustomer()==null||!a.userId().equals(r.getCustomer().getId()))throw ApiExceptions.notFound("Rental was not found");}
    private User lockActor(ActorPrincipal a){var user=em.find(User.class,a.userId(),LockModeType.PESSIMISTIC_WRITE);if(user==null)throw ApiExceptions.unauthorized("Actor no longer exists");return user;}
    private record PolicyPeriod(RenewalSources.Policy policy,com.storagehub.service.rental.period.RentalPeriod period){}
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks> localPaymentChecks;
    private record Gate(RenewalSources.Policy policy,RenewalSources.Eligibility eligibility,com.storagehub.service.rental.period.RentalPeriod period){}
    private PolicyPeriod policyPeriod(Rental r){
        var guard=operationalGuards==null?null:operationalGuards.getIfAvailable();
        if(guard!=null)guard.requireNoKnownConflict(r);
        if(r.getStatus()!=RentalStatus.active||r.getContractEndDate()==null)throw ApiExceptions.conflict("Move-out/non-active or incomplete rental blocks renewal");
        var period=periods==null?null:periods.require(r);if(period==null)throw deferred("Rental date provenance unknown");
        var source=policies.getIfAvailable();if(source==null)throw deferred("BO renewal policy missing");var p=source.read(r,period.endExclusive()).orElseThrow(()->deferred("BO renewal policy missing"));
        if(p.reference()==null||p.reference().isBlank()||p.version()==null||p.version().isBlank()||p.eligiblePackageIds()==null||p.paymentWindow()==null||p.paymentWindow().isNegative()||p.paymentWindow().isZero()||p.depositRate()==null)throw deferred("Incomplete BO policy");
        return new PolicyPeriod(p,period);
    }
    private Gate gate(Rental r,Instant now){
        var context=policyPeriod(r);var p=context.policy();var period=context.period();
        var simple=localPaymentChecks==null?null:localPaymentChecks.getIfAvailable();
        var es=eligibility.getIfAvailable();
        var e=simple!=null?new RenewalSources.Eligibility(true,false,true,simple.cutoff(r,now)):
            es==null?null:es.read(r,now).orElse(null);
        if(e==null)throw deferred("Eligibility UNKNOWN");
        if(!e.datesVerified()||e.recoveryCutoff()==null)throw deferred("Unverified date provenance/cutoff");
        if(e.recoveryBlocked()||!e.feasible()||!now.isBefore(e.recoveryCutoff()))throw ApiExceptions.conflict("Recovery/cutoff conflict");
        return new Gate(p,e,period);
    }
    private List<RenewalSources.Price> prices(Rental r,PolicyPeriod g){
        var s=pricing.getIfAvailable();if(s==null)throw deferred("Authoritative renewal pricing/rounding source missing");
        var data=s.read(r,g.period.endExclusive()).orElseThrow(()->deferred("Renewal pricing UNKNOWN"));
        if(r.getStorageUnit()==null||r.getStorageUnit().getUnitType()==null)throw ApiExceptions.conflict("Rental unit type missing");
        var codes=new HashSet<String>();var result=new ArrayList<RenewalSources.Price>();
        for(var p:data){if(p==null||p.packageId()==null)throw deferred("Incomplete pricing");if(!g.policy.eligiblePackageIds().contains(p.packageId()))continue;
            if(p.packageCode()==null||p.packageCode().isBlank()||p.packageCode().length()>30||p.packageVersion()==null||p.packageVersion().isBlank()||!Objects.equals(p.unitTypeId(),r.getStorageUnit().getUnitType().getId())||!"VND".equals(p.currency())||p.moneyScale()!=2||!codes.add(p.packageCode()))throw deferred("Pricing mapping/rounding incomplete or ambiguous");
            RenewalTermCalculator.calculate(g.period.lastPermittedDate(),p.months(),p.monthlyPrice(),p.discountRate(),BigDecimal.ZERO);result.add(p);}
        result.sort(Comparator.comparingInt(RenewalSources.Price::months).thenComparing(RenewalSources.Price::packageCode));return result;
    }
    private RenewalQuoteResponse.Terms terms(Rental r,Gate g,String code){
        return terms(r,g,code,null,false,null);
    }
    private RenewalQuoteResponse.Terms terms(Rental r,Gate g,String code,RenewalPaymentPlan plan,boolean fullPayment){
        return terms(r,g,code,plan,fullPayment,null);
    }
    private RenewalQuoteResponse.Terms terms(Rental r,Gate g,String code,RenewalPaymentPlan plan,boolean fullPayment,LocalDate customStart){
        var p=prices(r,new PolicyPeriod(g.policy(),g.period())).stream().filter(x->x.packageCode().equals(code)).findFirst().orElseThrow(()->ApiExceptions.conflict("Package not eligible for Renewal"));
        // Package discounts apply to full-term payment. Monthly installments use the catalog monthly price.
        var discount=plan==RenewalPaymentPlan.MONTHLY?BigDecimal.ZERO:p.discountRate();
        LocalDate startBase = customStart != null ? customStart : g.period.lastPermittedDate().plusDays(1);
        var t=RenewalTermCalculator.calculate(startBase,g.period.lastPermittedDate(),p.months(),p.monthlyPrice(),discount,fullPayment?BigDecimal.ONE:new BigDecimal("0.20"));
        var deposit=fullPayment?t.netRent():(plan==RenewalPaymentPlan.MONTHLY?p.monthlyPrice():t.netRent()).multiply(new BigDecimal("0.20")).setScale(0,java.math.RoundingMode.HALF_UP);
        var remaining=t.netRent().subtract(deposit);
        if(t.netRent().precision()>14)throw ApiExceptions.conflict("Shared renewal amount exceeds supported precision");
        return new RenewalQuoteResponse.Terms(r.getContractEndDate(),t.startDate(),t.endExclusive(),t.newEndDate(),p.unitTypeId(),p.packageCode(),p.packageId(),p.packageVersion(),p.months(),p.monthlyPrice(),discount,t.subtotal(),t.discountAmount(),t.netRent(),deposit,remaining,p.currency(),g.policy.reference(),g.policy.version(),r.getStorageUnit().getId(),r.getFacility().getId(),g.period.convention().name(),g.period.startDate(),fullPayment?null:plan);
    }
    private void requireCurrentTerms(Rental r,Gate g,RenewalQuoteResponse.Terms accepted){
        boolean full=accepted.paymentPlan()==null||accepted.remainingRentalAmount().signum()==0;
        var current=mapper.valueToTree(terms(r,g,accepted.pricingPackageCode(),accepted.paymentPlan(),full,accepted.extensionStartDate()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)current).set("renewalDepositAmount",mapper.valueToTree(accepted.renewalDepositAmount()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)current).set("remainingRentalAmount",mapper.valueToTree(accepted.remainingRentalAmount()));
        if(!store.canonical(accepted).equals(store.canonical(current)))
            throw ApiExceptions.conflict("Terms changed: new quote and Customer confirmation required");
    }
    private void requireSnapshot(Rental r,RenewalQuoteResponse.Terms t){if(!Objects.equals(r.getContractEndDate(),t.oldEndDate())||r.getStorageUnit()==null||r.getStorageUnit().getUnitType()==null||!Objects.equals(r.getStorageUnit().getId(),t.storageUnitId())||!Objects.equals(r.getFacility().getId(),t.facilityId())||!Objects.equals(r.getStorageUnit().getUnitType().getId(),t.unitTypeId()))throw ApiExceptions.conflict("Rental allocation/end changed; requote required");
        com.storagehub.service.ledger.FinancialContractSpecifications.validateRenewal(t);
        if(periods==null)throw deferred("Rental date provenance unknown");
        com.storagehub.service.rental.period.RenewalPeriodCompatibility.require(periods.require(r),t);
    }
    private static boolean isPrePayment(Renewal n){return n.getStatus()==RenewalStatus.pending||n.getStatus()==RenewalStatus.accepted;}
    private void requireCancelable(Renewal n){
        if(!isPrePayment(n))throw ApiExceptions.conflict("Chỉ có thể hủy yêu cầu gia hạn chưa hoàn tất thanh toán.");
        if(paymentRecords==null)return;
        for(var payment:paymentRecords.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId())) {
            if(payment.getStatus()==PaymentStatus.PAID||payment.getPaidAt()!=null||payment.getRenewalEvidenceRef()!=null
                ||payment.getRenewalLedgerReceiptRef()!=null)
                throw ApiExceptions.conflict("Đã ghi nhận tiền gia hạn; cần xử lý theo quy trình đối soát và hoàn tiền.");
            if(payment.getStatus()!=PaymentStatus.FAILED&&payment.getStatus()!=PaymentStatus.CANCELLED)
                throw ApiExceptions.conflict("Giao dịch VNPay chưa có kết quả cuối cùng. Hệ thống đang đối soát; chỉ có thể hủy sau khi xác nhận giao dịch chưa thu tiền.");
        }
    }
    private void requirePrePayment(Renewal n){
        if(!isPrePayment(n))throw ApiExceptions.conflict("Only an unstarted Renewal may use this D2 command");
        if(paymentRecords!=null&&!paymentRecords.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).isEmpty())
            throw ApiExceptions.conflict("Renewal terms cannot change after a VNPay payment intent was created");
    }
    public RenewalResponse approve(ActorPrincipal a, UUID id) {
        var n = em.find(Renewal.class, id);
        if (n == null) throw ApiExceptions.notFound("Renewal was not found");
        var user = lockActor(a);
        var r = n.getRental();
        em.refresh(r);
        if (n.getNewEndDate() != null) {
            r.setContractEndDate(n.getNewEndDate());
        }
        n.setStatus(RenewalStatus.completed);
        var wf = em.find(RenewalWorkflow.class, id);
        if (wf != null) {
            wf.reviewed(user, now(), "Approved by staff", null, null);
        }
        var selectedState = em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class, id);
        if (selectedState != null) {
            selectedState.completed(user.getId(), now());
        }
        em.flush();
        return reads.detail(a, id, true);
    }

    public RenewalResponse reject(ActorPrincipal a, UUID id, String reason) {
        var n = em.find(Renewal.class, id);
        if (n == null) throw ApiExceptions.notFound("Renewal was not found");
        var user = lockActor(a);
        n.setStatus(RenewalStatus.rejected);
        var wf = em.find(RenewalWorkflow.class, id);
        if (wf != null) {
            wf.reviewed(user, now(), reason != null ? reason : "Rejected by staff", null, null);
        }
        em.flush();
        return reads.detail(a, id, true);
    }

    private <T>T decode(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception e){throw ApiExceptions.conflict("Stored Renewal snapshot is invalid");}}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
}

