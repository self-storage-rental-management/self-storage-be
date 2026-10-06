package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.ReservationReviewDecision;
import com.storagehub.api.reservation.ReservationReviewRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationGoodsReviewServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationGoodsItemRepository goodsItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private AuditLogService auditLogService;

    private ReservationGoodsReviewService service;
    private ActorPrincipal staff;
    private User reviewer;
    private Reservation reservation;
    private ReservationGoodsItem goodsItem;

    @BeforeEach
    void setUp() {
        service = new ReservationGoodsReviewService(
            reservationRepository, goodsItemRepository, userRepository,
            authorizationService, facilityScopeService, auditLogService
        );
        reviewer = entityWithId(new User());
        staff = new ActorPrincipal(
            reviewer.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of("view_reservations", "approve_reservations"), Map.of()
        );

        Facility facility = entityWithId(new Facility());
        UnitType unitType = entityWithId(new UnitType());
        User customer = entityWithId(new User());
        customer.setEmail("customer@example.com");

        reservation = entityWithId(new Reservation());
        reservation.setReservationCode("RSV-REVIEW001");
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setCustomer(customer);
        reservation.setStatus(ReservationStatus.AWAITING_REVIEW);
        reservation.setGoodsReviewStatus(GoodsReviewStatus.PENDING);
        reservation.setStartDate(LocalDate.of(2026, 10, 10));
        reservation.setEndDate(LocalDate.of(2026, 11, 10));
        reservation.setGoodsReviewDueAt(Instant.now().plusSeconds(3600));

        goodsItem = entityWithId(new ReservationGoodsItem());
        goodsItem.setReservation(reservation);
        goodsItem.setCategory(GoodsCategory.OTHER);
        goodsItem.setQuantity(1);
        goodsItem.setLengthCm(BigDecimal.ONE);
        goodsItem.setWidthCm(BigDecimal.ONE);
        goodsItem.setHeightCm(BigDecimal.ONE);
        goodsItem.setWeightKg(BigDecimal.ONE);
        goodsItem.setRequiresStaffReview(true);
        goodsItem.setReviewStatus(GoodsReviewStatus.PENDING);
    }

    @Test
    void approvalMovesReservationToPayment() {
        mockReviewData();

        var response = service.review(
            staff, reservation.getId(),
            new ReservationReviewRequest(ReservationReviewDecision.APPROVE, "Hàng an toàn")
        );

        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(response.getGoodsReviewStatus()).isEqualTo(GoodsReviewStatus.APPROVED);
        assertThat(goodsItem.getReviewStatus()).isEqualTo(GoodsReviewStatus.APPROVED);
        assertThat(reservation.getPaymentExpiresAt()).isAfter(Instant.now());
        assertThat(reservation.getPaymentExpiresAt()).isBefore(Instant.now().plus(25, ChronoUnit.HOURS));
        assertThat(reservation.getPaymentExpiresAt()).isAfter(Instant.now().plus(23, ChronoUnit.HOURS));
    }

    @Test
    void rejectionReleasesReservation() {
        mockReviewData();

        var response = service.review(
            staff, reservation.getId(),
            new ReservationReviewRequest(ReservationReviewDecision.REJECT, "Vật liệu dễ cháy")
        );

        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservation.getRejectionReason()).isEqualTo("Vật liệu dễ cháy");
        assertThat(goodsItem.getReviewStatus()).isEqualTo(GoodsReviewStatus.REJECTED);
    }

    @Test
    void rejectionRequiresReason() {
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.review(
            staff, reservation.getId(),
            new ReservationReviewRequest(ReservationReviewDecision.REJECT, " ")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("note is required when rejecting goods");
    }

    @Test
    void customerCannotReviewGoods() {
        ActorPrincipal customer = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        assertThatThrownBy(() -> service.review(
            customer, reservation.getId(),
            new ReservationReviewRequest(ReservationReviewDecision.APPROVE, null)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Only staff can review reservation goods");
    }

    @Test
    void expiredReviewCannotBeApproved() {
        reservation.setGoodsReviewDueAt(Instant.now().minusSeconds(1));
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.review(
            staff, reservation.getId(),
            new ReservationReviewRequest(ReservationReviewDecision.APPROVE, null)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation goods review period has expired");
    }

    private void mockReviewData() {
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(userRepository.findById(staff.userId())).thenReturn(Optional.of(reviewer));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(goodsItem));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
