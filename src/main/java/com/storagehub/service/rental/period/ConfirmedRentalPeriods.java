package com.storagehub.service.rental.period;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Append-only confirmed periods. A changed Rental cannot silently reuse an older confirmation. */
@Service @RequiredArgsConstructor
public class ConfirmedRentalPeriods {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${storagehub.integration.rental-period.inclusive-end:false}")
    private boolean inclusiveEnd;

    public boolean contains(Rental r) {
        return jdbc.queryForObject("select count(*) from confirmed_rental_periods where rental_id=?",Long.class,r.getId().toString()) > 0;
    }
    public Optional<RentalPeriod> read(Rental r) {
        if(r==null||r.getId()==null||r.getReservation()==null||r.getCustomer()==null||r.getFacility()==null||r.getStorageUnit()==null)return Optional.empty();
        var rows=jdbc.queryForList("select * from confirmed_rental_periods where rental_id=? order by sequence_no desc limit 1",r.getId().toString());
        if(rows.isEmpty())return Optional.empty();
        var row=rows.getFirst();
        if(!r.getReservation().getId().toString().equals(row.get("reservation_id"))
            ||!r.getCustomer().getId().toString().equals(row.get("customer_id"))
            ||!r.getFacility().getId().toString().equals(row.get("facility_id"))
            ||!r.getStorageUnit().getId().toString().equals(row.get("storage_unit_id")))return Optional.empty();
        return RentalPeriod.verified(r.getId(),((java.sql.Date)row.get("start_date")).toLocalDate(),
            ((java.sql.Date)row.get("stored_end_date")).toLocalDate(),RentalPeriod.Convention.valueOf((String)row.get("end_convention")),
            (String)row.get("source_ref")).filter(p->p.matches(r));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void receipt(Rental r) {
        var p=RentalPeriod.verified(r.getId(),r.getStartDate(),r.getContractEndDate(),
            inclusiveEnd?RentalPeriod.Convention.INCLUSIVE:RentalPeriod.Convention.EXCLUSIVE,"confirmed-receipt:"+r.getReservation().getId())
            .orElseThrow(()->ApiExceptions.conflict("Kỳ thuê nhận kho không hợp lệ."));
        confirm(r,p);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(Rental r,RentalPeriod period) {
        if(!period.matches(r))throw ApiExceptions.conflict("Kỳ thuê xác nhận không khớp hồ sơ thuê.");
        var existing=jdbc.queryForObject("select count(*) from confirmed_rental_periods where rental_id=? and source_ref=?",Long.class,r.getId().toString(),period.reference());
        if(existing>0) {
            if(read(r).filter(p->p.equals(period)).isEmpty())throw ApiExceptions.conflict("Nguồn xác nhận kỳ thuê đã thay đổi.");
            return;
        }
        jdbc.update("insert into confirmed_rental_periods(rental_id,reservation_id,customer_id,facility_id,storage_unit_id,start_date,stored_end_date,end_convention,source_ref,confirmed_at) values(?,?,?,?,?,?,?,?,?,?)",
            r.getId().toString(),r.getReservation().getId().toString(),r.getCustomer().getId().toString(),r.getFacility().getId().toString(),r.getStorageUnit().getId().toString(),
            java.sql.Date.valueOf(period.startDate()),java.sql.Date.valueOf(period.storedEndDate()),period.convention().name(),period.reference(),java.sql.Timestamp.from(Instant.now()));
    }
}
