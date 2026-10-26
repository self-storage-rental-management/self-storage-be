package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.util.*;

/** Reads customer-visible values from the immutable quote captured by a reservation. */
final class ReservationSnapshotView {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String MISSING = "Thiếu dữ liệu lịch sử, cần đối soát";
    private static com.fasterxml.jackson.databind.JsonNode unit(Reservation reservation) {
        try {
            var quote=reservation.getSourceQuote();
            if(quote==null||quote.getUnitTypeSnapshotJson()==null||quote.getUnitTypeSnapshotJson().isBlank())return null;
            var data=JSON.readTree(quote.getUnitTypeSnapshotJson());return data!=null&&data.isObject()?data:null;
        } catch(Exception invalid){return null;}
    }

    private ReservationSnapshotView() {}

    static String unitTypeName(Reservation reservation) {
        var data=unit(reservation);var name=data==null?null:data.path("name");
        return name!=null&&name.isTextual()&&!name.asText().isBlank()?name.asText():MISSING;
    }

    static java.math.BigDecimal unitTypeDecimal(Reservation reservation, String field) {
        if(!Set.of("lengthM","widthM","heightM","maxLoadKg","rackLengthM","rackWidthM","rackHeightM").contains(field))
            throw new IllegalArgumentException("Unsupported unit type snapshot field: "+field);
        try {
            var data=unit(reservation);var value=data==null?null:data.path(field);
            if(value!=null&&(value.isNumber()||value.isTextual())) {
                var number=new BigDecimal(value.asText());
                if(number.signum()>=0&&number.precision()<=18&&Math.abs(number.scale())<=8)return number;
            }
        } catch(NumberFormatException invalid) { /* Do not read the live catalog. */ }
        throw ApiExceptions.conflict(MISSING+": thông số kho "+field);
    }

    static int unitTypeRackCount(Reservation reservation) {
        try {
            var data=unit(reservation);var value=data==null?null:data.path("rackCount");
            if(value!=null&&(value.isNumber()||value.isTextual())) {
                int count=new BigDecimal(value.asText()).intValueExact();if(count>=0)return count;
            }
        } catch(ArithmeticException|NumberFormatException invalid) { /* Missing or invalid historical count. */ }
        throw ApiExceptions.conflict(MISSING+": số khung kệ");
    }
    private static boolean same(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.signum()>=0&&a.compareTo(b)==0;}
    static boolean pricingComplete(Reservation r,ReservationPricingSnapshot s) {
        var q=r.getSourceQuote();
        return s!=null&&q!=null&&q.getId()!=null&&s.getReservation()!=null&&Objects.equals(s.getReservation().getId(),r.getId())
            &&s.getPricingPolicyVersion()!=null&&!s.getPricingPolicyVersion().isBlank()&&s.getPricingPolicyVersion().equals(q.getPolicyVersion())
            &&s.getPricingPackageCode()!=null&&s.getPricingPackageCode().equals(q.getPricingPackageCode())
            &&s.getRentalMonths()>0&&s.getRentalMonths()==q.getRentalMonths()&&s.getFreeSetupDays()>=0
            &&s.getFreeSetupDays()==q.getFreeSetupDays()&&s.getFreeSetupDays()==r.getSetupDaysSnapshot()
            &&s.getQuotedAt()!=null&&s.getQuotedAt().equals(q.getQuotedAt())
            &&same(s.getMonthlyPrice(),q.getMonthlyPrice())&&same(s.getGrossRentalAmount(),q.getSubtotal())
            &&Objects.equals(s.getSetupEarlyExitPolicyVersion(),q.getSetupEarlyExitPolicyVersion())
            &&(s.getSetupEarlyExitPolicyVersion()==null || same(s.getSetupEarlyExitFee(),q.getSetupEarlyExitFee()))
            &&same(s.getDiscountRate(),q.getDiscountRate())&&same(s.getDiscountAmount(),q.getDiscountAmount())
            &&same(s.getNetRentalAmount(),q.getTotalAfterDiscount())&&same(s.getReservationDepositAmount(),q.getReservationDepositAmount())
            &&same(s.getSecurityDepositAmount(),q.getSecurityDepositAmount())&&same(s.getRemainingRentalAmount(),q.getRemainingRentalAmount())
            &&same(s.getDueAtCheckIn(),q.getDueAtCheckIn())&&same(s.getTotalInitialObligation(),q.getTotalInitialObligation());
    }
    static ReservationResponse missingResponse(Reservation r) {
        var response=new ReservationResponse(r.getId(),r.getReservationCode(),r.getSourceQuote()==null?null:r.getSourceQuote().getId(),
            r.getFacility().getId(),r.getUnitType().getId(),r.getStatus(),r.getGoodsReviewStatus(),r.getCompatibilityResult(),
            r.getStartDate(),r.getEndDate(),null,null,null,null,null,null,r.getTotalGoodsVolumeM3(),r.getTotalGoodsWeightKg(),r.getHoldExpiresAt(),r.getCreatedAt());
        response.setUnitTypeName(unitTypeName(r));response.setCustomerName(r.getCustomer().getFullName());
        response.setCustomerEmail(r.getCustomer().getEmail());response.setAppointmentAt(r.getAppointmentAt());
        if(r.getAssignedUnit()!=null){response.setAssignedUnitId(r.getAssignedUnit().getId());response.setAssignedUnitCode(r.getAssignedUnit().getCode());}
        describe(response,r,null);return response;
    }
    static int requireSetupDays(Reservation r,ReservationPricingSnapshot snapshot) {
        if(!pricingComplete(r,snapshot))throw ApiExceptions.conflict(MISSING+": giá, policy hoặc số ngày setup đã chấp nhận");
        return snapshot.getFreeSetupDays();
    }
    static void describe(ReservationResponse response,Reservation r,ReservationPricingSnapshot snapshot) {
        var warnings=new ArrayList<String>();
        boolean pricing=pricingComplete(r,snapshot);
        response.setPricingSnapshotStatus(pricing?"COMPLETE":"INCOMPLETE");
        response.setPricingPolicyVersion(snapshot==null?null:snapshot.getPricingPolicyVersion());
        response.setPricingQuotedAt(snapshot==null?null:snapshot.getQuotedAt());
        response.setPricingExpiresAt(snapshot==null?null:snapshot.getExpiresAt());
        response.setFreeSetupDays(pricing?snapshot.getFreeSetupDays():null);
        response.setSetupEarlyExitPolicyVersion(pricing?snapshot.getSetupEarlyExitPolicyVersion():null);
        response.setSetupEarlyExitFee(pricing?snapshot.getSetupEarlyExitFee():null);
        if(!pricing)warnings.add(MISSING+": giá, policy hoặc số ngày setup");
        try {
            if(MISSING.equals(unitTypeName(r)))throw ApiExceptions.conflict(MISSING);
            var length=unitTypeDecimal(r,"lengthM");var width=unitTypeDecimal(r,"widthM");var height=unitTypeDecimal(r,"heightM");
            response.setUnitTypeSnapshot(new ReservationResponse.UnitTypeSnapshot(unitTypeName(r),length,width,height,
                length.multiply(width),length.multiply(width).multiply(height),unitTypeDecimal(r,"maxLoadKg"),unitTypeRackCount(r),
                unitTypeDecimal(r,"rackLengthM"),unitTypeDecimal(r,"rackWidthM"),unitTypeDecimal(r,"rackHeightM")));
        } catch(com.storagehub.common.api.ApiException missing) {
            response.setUnitTypeSnapshot(null);warnings.add(MISSING+": tên hoặc thông số loại kho");
        }
        response.setSnapshotWarnings(List.copyOf(warnings));
    }
}
