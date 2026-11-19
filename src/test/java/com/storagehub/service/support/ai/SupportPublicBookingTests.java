package com.storagehub.service.support.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storagehub.api.facility.AvailabilityResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.FacilityRepository.PublicContact;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AvailabilityQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupportPublicBookingTests {
    private final FacilityRepository facilities = mock(FacilityRepository.class);
    private final UnitTypeRepository unitTypes = mock(UnitTypeRepository.class);
    private final AvailabilityQueryService availability = mock(AvailabilityQueryService.class);
    private final SupportPublicBooking booking = new SupportPublicBooking(new SupportPublicFacility(facilities),
        facilities, unitTypes, availability);
    private final ActorPrincipal customer = mock(ActorPrincipal.class);
    private final UUID facilityId = UUID.randomUUID(), unitTypeId = UUID.randomUUID();

    @BeforeEach
    void publishOneType() {
        var contact = mock(PublicContact.class);
        when(contact.getName()).thenReturn("Kho Việt – Cơ sở Quận 1");
        when(contact.getCode()).thenReturn("HCM-Q1-F01");
        when(facilities.findPublicContacts(FacilityStatus.active)).thenReturn(List.of(contact));
        var facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        when(facility.getName()).thenReturn("Kho Việt – Cơ sở Quận 1");
        when(facility.getStatus()).thenReturn(FacilityStatus.active);
        when(facilities.findByCodeIgnoreCase("HCM-Q1-F01")).thenReturn(Optional.of(facility));
        var type = mock(UnitType.class);
        when(type.getId()).thenReturn(unitTypeId);
        when(type.getName()).thenReturn("Kho vừa");
        when(type.getStatus()).thenReturn(UnitTypeStatus.active);
        when(type.getMonthlyPrice()).thenReturn(new BigDecimal("2900000"));
        when(unitTypes.findByFacility_IdAndCode(facilityId, "M")).thenReturn(Optional.of(type));
    }

    @Test
    void readsListedPriceForExplicitPublishedType() {
        var lookup = new SupportAiProvider.Lookup("Quận 1", "M", null, null);
        assertThat(booking.answer(customer, SupportAiProvider.Action.PRICE, lookup, "Kho M ở Quận 1 bao nhiêu tiền?"))
            .contains("2.900.000 ₫/tháng", "đơn giá niêm yết");
        assertThat(booking.answer(customer, SupportAiProvider.Action.PRICE, lookup, "Kho M giá bao nhiêu?"))
            .contains("cơ sở nào").doesNotContain("2.900.000");
        verifyNoInteractions(availability);
    }

    @Test
    void requiresExplicitDatesAndUsesRecordedAvailability() {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2), end = start.plusMonths(1);
        var lookup = new SupportAiProvider.Lookup("Quận 1", "M", start.toString(), end.toString());
        assertThat(booking.answer(customer, SupportAiProvider.Action.AVAILABILITY, lookup, "Kho M ở Quận 1 còn chỗ không?"))
            .contains("ngày bắt đầu và ngày kết thúc");
        verifyNoInteractions(availability);
        when(availability.get(customer, facilityId, unitTypeId, start, end)).thenReturn(
            new AvailabilityResponse(facilityId, unitTypeId, start, end, 2, true));
        assertThat(booking.answer(customer, SupportAiProvider.Action.AVAILABILITY, lookup,
            "Kho M ở Quận 1 còn chỗ từ " + start + " đến " + end + " không?")).contains("2 gian trống");
    }
}
