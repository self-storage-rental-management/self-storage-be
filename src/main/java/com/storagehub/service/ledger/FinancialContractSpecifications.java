package com.storagehub.service.ledger;

import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.renewal.persistence.RenewalAcceptedRevision;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Amount/source mapping only. An accepted price never authorizes a debt event or due timestamp. */
public final class FinancialContractSpecifications {
    private FinancialContractSpecifications() {}
    public record Specification(UUID rentalId, UUID renewalId, LedgerStore.Kind kind,
        BigDecimal amount, String sourceReference, java.time.LocalDate dueDate) {
        public Specification(UUID rentalId,UUID renewalId,LedgerStore.Kind kind,BigDecimal amount,String sourceReference){this(rentalId,renewalId,kind,amount,sourceReference,null);}
        public Instant requireDue(Instant authoritativeDue) {
            if(dueDate!=null)return dueDate.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
            if (authoritativeDue == null) throw ApiExceptions.conflict("FIN-02: authoritative effective event and due date required");
            return authoritativeDue;
        }
    }
    public static List<Specification> initial(Rental rental, ReservationPricingSnapshot s) {
        if (rental == null || rental.getReservation() == null || s == null || s.getReservation() == null
            || s.getId() == null || rental.getId() == null || !same(rental.getReservation(), s.getReservation())
            || !same(rental.getCustomer(), s.getReservation().getCustomer())
            || !same(rental.getFacility(), s.getReservation().getFacility())) fail("Initial source identity mismatch");
        validateInitial(s);
        String ref = "reservation-snapshot:" + s.getId();
        return List.of(new Specification(rental.getId(), null, LedgerStore.Kind.RENT, s.getNetRentalAmount(), ref + ":RENT"),
            new Specification(rental.getId(), null, LedgerStore.Kind.SECURITY_DEPOSIT, s.getSecurityDepositAmount(), ref + ":SECURITY_DEPOSIT"));
    }
    public static void validateInitial(ReservationPricingSnapshot s) {
        if (s == null || s.getRentalMonths() < 1 || s.getPricingPackageCode() == null || s.getPricingPackageCode().isBlank()
            || s.getPricingPolicyVersion() == null || s.getPricingPolicyVersion().isBlank()) fail("Initial pricing identity incomplete");
        for (var v : Arrays.asList(s.getMonthlyPrice(),s.getGrossRentalAmount(),s.getDiscountAmount(),s.getNetRentalAmount(),
            s.getReservationDepositAmount(),s.getSecurityDepositAmount(),s.getRemainingRentalAmount(),s.getDueAtCheckIn(),s.getTotalInitialObligation())) money(v);
        rate(s.getDiscountRate());
        equal(s.getMonthlyPrice().multiply(BigDecimal.valueOf(s.getRentalMonths())), s.getGrossRentalAmount());
        equal(s.getGrossRentalAmount().multiply(s.getDiscountRate()).setScale(2,RoundingMode.HALF_UP), s.getDiscountAmount());
        equal(s.getGrossRentalAmount().subtract(s.getDiscountAmount()), s.getNetRentalAmount());
        equal(s.getReservationDepositAmount().add(s.getRemainingRentalAmount()),s.getNetRentalAmount());
        equal(s.getRemainingRentalAmount().add(s.getSecurityDepositAmount()),s.getDueAtCheckIn());
        equal(s.getNetRentalAmount().add(s.getSecurityDepositAmount()),s.getTotalInitialObligation());
    }
    public static void validateRenewal(RenewalQuoteResponse.Terms t) {
        if (t == null || !"VND".equals(t.currency()) || t.rentalMonths()<1 || t.packagePolicyRef()==null
            || t.pricingPackageCode()==null || t.pricingPackageCode().isBlank() || t.packagePolicyVersion()==null || t.packagePolicyVersion().isBlank()
            || t.renewalPolicyRef()==null || t.renewalPolicyRef().isBlank() || t.renewalPolicyVersion()==null || t.renewalPolicyVersion().isBlank()) fail("Renewal pricing identity incomplete");
        for(var v:Arrays.asList(t.monthlyPrice(),t.subtotal(),t.discountAmount(),t.totalAfterDiscount(),t.renewalDepositAmount(),t.remainingRentalAmount()))money(v);
        rate(t.discountRate());
        equal(t.monthlyPrice().multiply(BigDecimal.valueOf(t.rentalMonths())),t.subtotal());
        equal(t.subtotal().multiply(t.discountRate()).setScale(2,RoundingMode.HALF_UP),t.discountAmount());
        equal(t.subtotal().subtract(t.discountAmount()),t.totalAfterDiscount());
        equal(t.renewalDepositAmount().add(t.remainingRentalAmount()),t.totalAfterDiscount());
        if(t.paymentPlan()!=null) {
            equal(t.discountRate(),BigDecimal.ZERO);equal(t.discountAmount(),BigDecimal.ZERO);
            equal(t.renewalDepositAmount(),(t.monthly()?t.monthlyPrice():t.totalAfterDiscount()).multiply(new BigDecimal("0.20")).setScale(0,RoundingMode.HALF_UP));
            if(t.monthlyPrice().stripTrailingZeros().scale()>0)fail("Giá thuê tháng phải là số nguyên VND");
        }
    }
    public static List<Specification> accepted(RenewalAcceptedRevision revision, RenewalQuoteResponse.Terms t) {
        validateRenewal(t);
        if(revision==null || revision.getId()==null || revision.getAcceptedAt()==null || revision.getRevisionNumber()<1
            || revision.getQuote()==null || revision.getQuote().getId()==null || revision.getRenewal()==null) fail("Accepted revision missing");
        var n=revision.getRenewal();var q=revision.getQuote();var r=n.getRental();
        try {
            var jsonMapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var decoded=jsonMapper.readValue(q.getTermsJson(),RenewalQuoteResponse.Terms.class);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(q.getTermsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(!jsonMapper.valueToTree(decoded).equals(jsonMapper.valueToTree(t))||!hash.equals(q.getTermsHash()))fail("Accepted quote content/hash mismatch");
            if(revision.getAcceptedAt().isBefore(q.getQuotedAt())||!revision.getAcceptedAt().isBefore(q.getExpiresAt()))fail("Quote was not accepted within its lifetime");
        } catch(com.storagehub.common.api.ApiException e){throw e;}catch(Exception e){fail("Accepted quote content/hash invalid");}
        if(n.getId()==null || r==null || r.getId()==null || !same(r,q.getRental()) || !same(r.getCustomer(),q.getCustomer())
            || !same(r.getCustomer(),n.getRequestedBy()) || !Objects.equals(r.getFacility().getId(),t.facilityId())
            || !Objects.equals(r.getStorageUnit().getId(),t.storageUnitId()) || !Objects.equals(n.getNewEndDate(),t.newEndDate()))fail("Accepted revision identity mismatch");
        equal(n.getAmount(),t.totalAfterDiscount());
        String ref="renewal-accepted:"+revision.getId()+":quote:"+q.getId();
        if(t.monthly()) {
            var specs=new ArrayList<Specification>();
            specs.add(new Specification(r.getId(),n.getId(),LedgerStore.Kind.RENEWAL_DEPOSIT,t.renewalDepositAmount(),ref+":DEPOSIT"));
            specs.add(new Specification(r.getId(),n.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,t.dueAtSigning(),ref+":REMAINDER"));
            for(int i=1;i<t.rentalMonths();i++)specs.add(new Specification(r.getId(),n.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,
                t.monthlyPrice(),ref+":MONTH:"+(i+1),t.extensionStartDate().plusMonths(i)));
            return List.copyOf(specs);
        }
        if (t.renewalDepositAmount().signum() == 0) {
            // Previously accepted no-deposit terms are represented by one full-period payable
            // obligation. Keep the legacy kind to avoid a schema/API enum change.
            return List.of(new Specification(r.getId(), n.getId(), LedgerStore.Kind.RENEWAL_REMAINDER,
                t.totalAfterDiscount(), ref + ":FULL_RENT"));
        }
        // Immutable quote: preserve its accepted deposit/remainder split.
        return List.of(new Specification(r.getId(),n.getId(),LedgerStore.Kind.RENEWAL_DEPOSIT,t.renewalDepositAmount(),ref+":DEPOSIT"),
            new Specification(r.getId(),n.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,t.remainingRentalAmount(),ref+":REMAINDER"));
    }
    public static List<Specification> signing(List<Specification> specs){return specs.stream().filter(s->s.dueDate()==null).toList();}
    private static boolean same(BaseEntity a,BaseEntity b){return a!=null&&b!=null&&a.getId()!=null&&a.getId().equals(b.getId());}
    private static void rate(BigDecimal v){if(v==null||v.signum()<0||v.compareTo(BigDecimal.ONE)>0)fail("Invalid discount rate");}
    private static void money(BigDecimal v){if(v==null||v.signum()<0)fail("Missing or negative contractual amount");try{if(v.setScale(2,RoundingMode.UNNECESSARY).precision()>14)fail("Amount precision unsupported");}catch(ArithmeticException e){fail("Amount rounding unsupported");}}
    private static void equal(BigDecimal a,BigDecimal b){if(a==null||b==null||a.compareTo(b)!=0)fail("Contract amount relationships inconsistent");}
    private static void fail(String s){throw ApiExceptions.conflict(s);}
}
