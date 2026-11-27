package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerCashflowService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final EntityManager em;
    private final AdminAuthorizationService authorization;
    private final FacilityScopeService scopes;
    public record Event(String id, String direction, BigDecimal amount, Instant occurredAt, String reference) {}
    public record Day(String day, BigDecimal collected, BigDecimal refunded, BigDecimal net) {}
    public record Month(String month, BigDecimal collected, BigDecimal refunded, BigDecimal net) {}
    public record RefundLine(UUID id, String reservationCode, String customerName, String status, String reason,
        BigDecimal deposit, BigDecimal rentalCredit, BigDecimal deductions, BigDecimal refund,
        Instant paidAt, String reference, boolean payoutRecorded, String receiptCode) {}
    public record Report(UUID facilityId, LocalDate from, LocalDate to, Instant snapshotAt, String completeness,
        BigDecimal collected, BigDecimal refunded, BigDecimal netRetained, BigDecimal pendingRefund,
        BigDecimal pendingApprovalRefund, BigDecimal securityDepositHeld, List<Day> daily, List<Month> monthly,
        List<RefundLine> settlements, List<String> warnings, LocalDate firstPaymentDate) {}

    @Transactional(readOnly = true)
    public Report read(ActorPrincipal actor, UUID facilityId, LocalDate from, LocalDate to) {
        authorization.require(actor, SystemPermission.VIEW_REPORTS);
        scopes.assertCanRead(actor, facilityId);
        LocalDate today = LocalDate.now(ZONE);
        if ((from != null && from.isAfter(today)) || (to != null && to.isAfter(today)))
            throw ApiExceptions.validation("Không được chọn ngày trong tương lai.", null);
        if (from != null && to != null && from.isAfter(to))
            throw ApiExceptions.validation("Ngày bắt đầu không được sau ngày kết thúc.", null);
        Instant now = Instant.now();
        var warnings = new LinkedHashSet<String>();
        var payments = em.createQuery("""
            select p from Payment p left join p.reservation r left join p.renewal n left join n.rental rental
            where p.status = :paid and (r.facility.id = :facility or rental.facility.id = :facility)
            """, Payment.class).setParameter("paid", PaymentStatus.PAID).setParameter("facility", facilityId).getResultList();
        var recorded = new HashMap<UUID, Payment>();
        var events = new ArrayList<Event>();
        for (Payment p : payments) {
            Instant at = paymentTimestamp(p);
            if (!validAmount(p.getAmount()) || at == null || !"VND".equals(p.getCurrency())) {
                warnings.add("Có giao dịch đã ghi nhận thiếu số tiền, thời gian hoặc không phải VND; cần bổ sung dữ liệu."); continue;
            }
            recorded.put(p.getId(), p);
            events.add(new Event("payment:" + p.getId(), "COLLECTION", p.getAmount(), at, p.getGatewayTransactionNo()));
        }
        LocalDate firstPaymentDate = events.stream()
            .filter(event -> "COLLECTION".equals(event.direction()))
            .filter(event -> event.amount() != null && event.amount().signum() > 0)
            .map(Event::occurredAt)
            .filter(Objects::nonNull)
            .map(timestamp -> timestamp.atZone(ZONE).toLocalDate())
            .min(LocalDate::compareTo)
            .orElse(null);
        if (firstPaymentDate == null && (from != null || to != null))
            throw ApiExceptions.validation("Cơ sở chưa có thanh toán đã ghi nhận để chọn khoảng báo cáo.", null);
        if (firstPaymentDate != null && from != null && from.isBefore(firstPaymentDate))
            throw ApiExceptions.validation("Ngày bắt đầu phải từ ngày thanh toán đầu tiên tại cơ sở.", null);
        LocalDate first = from == null ? (firstPaymentDate == null ? today : firstPaymentDate) : from;
        LocalDate last = to == null ? today : to;
        if (first.isAfter(last) || last.isAfter(today))
            throw ApiExceptions.validation("Chọn khoảng báo cáo từ ngày thanh toán đầu tiên đến ngày hiện tại.", null);
        var returns = em.createQuery("select r from ReturnCase r where r.facility.id = :facility", ReturnCase.class)
            .setParameter("facility", facilityId).getResultList();
        var requests = em.createQuery("""
            select q from RefundRequest q left join q.payment.reservation r left join q.payment.renewal n left join n.rental rental
            where r.facility.id=:facility or rental.facility.id=:facility
            """, RefundRequest.class).setParameter("facility", facilityId).getResultList();
        var refunds = em.createQuery("""
            select q from Refund q left join q.payment.reservation r left join q.payment.renewal n left join n.rental rental
            where r.facility.id=:facility or rental.facility.id=:facility
            """, Refund.class).setParameter("facility", facilityId).getResultList();
        var lines = new ArrayList<RefundLine>();
        var settledRentals = new HashSet<UUID>();
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal pendingApproval = BigDecimal.ZERO;
        for (ReturnCase rc : returns) {
            boolean completed = rc.getStatus() == ReturnCaseStatus.completed;
            Instant at = rc.getSettlementPaidAt();
            if (completed) {
                settledRentals.add(rc.getRental().getId());
                BigDecimal amount = money(rc.getNetRefundAmount()).signum() > 0 ? rc.getNetRefundAmount() : rc.getAmountDueFromCustomer();
                if (validAmount(amount) && amount.signum() > 0 && at != null)
                    events.add(new Event("return:" + rc.getId(), money(rc.getNetRefundAmount()).signum() > 0 ? "REFUND" : "COLLECTION", amount, at, rc.getSettlementPaymentId()));
                else if (money(amount).signum() > 0) warnings.add("Có quyết toán hoàn tất chưa ghi ngày thanh toán; chưa thể phân bổ vào kỳ báo cáo.");
            }
            if (rc.getStatus() == ReturnCaseStatus.refund_pending) {
                pending = pending.add(positiveMoney(rc.getNetRefundAmount()));
            } else if (pendingApprovalStatus(rc.getStatus())) {
                pendingApproval = pendingApproval.add(positiveMoney(rc.getNetRefundAmount()));
            }
            if (money(rc.getNetRefundAmount()).signum() > 0 || money(rc.getTotalDeductions()).signum() > 0)
                lines.add(new RefundLine(rc.getId(), rc.getRental().getReservation().getReservationCode(), rc.getCustomer().getFullName(),
                    rc.getStatus().name(), rc.getReturnType().name(), money(rc.getDepositAmount()), money(rc.getRentalRefundAmount()), money(rc.getTotalDeductions()),
                    money(rc.getNetRefundAmount()), at, rc.getSettlementPaymentId(), completed, rc.getSettlementReceiptCode()));
        }
        // ReturnCase is the canonical settlement; linked request/execution rows are not additional cash.
        for (RefundRequest request : requests) {
            if (request.getReturnCase() != null) continue;
            boolean completed = request.getStatus() == RefundRequestStatus.REFUNDED;
            if (Set.of(RefundRequestStatus.APPROVED, RefundRequestStatus.PROCESSING).contains(request.getStatus())) {
                pending = pending.add(positiveMoney(request.getAmount()));
            } else if (Set.of(RefundRequestStatus.REQUESTED, RefundRequestStatus.PENDING_ESCALATION).contains(request.getStatus())) {
                pendingApproval = pendingApproval.add(positiveMoney(request.getAmount()));
            }
            boolean hasExecution = refunds.stream().anyMatch(f -> f.getStatus() == RefundStatus.COMPLETED
                && f.getPayment().getId().equals(request.getPayment().getId())
                && sameReference(f.getGatewayRefundId(), request.getGatewayRefundId()));
            Instant at = request.getProcessedAt() != null ? request.getProcessedAt() : request.getUpdatedAt();
            if (completed && !hasExecution && validAmount(request.getAmount()) && at != null)
                events.add(new Event("refund-request:" + request.getId(), "REFUND", request.getAmount(), at, request.getGatewayRefundId()));
            var p = request.getPayment();
            var reservation = p.getReservation() != null ? p.getReservation() : p.getRenewal().getRental().getReservation();
            lines.add(new RefundLine(request.getId(), reservation.getReservationCode(), reservation.getCustomerName(),
                request.getStatus().name(), request.getReason(), null, null, null, request.getAmount(), completed ? at : null, request.getGatewayRefundId(), completed, null));
        }
        for (Refund refund : refunds) {
            boolean linkedRequest = requests.stream().anyMatch(q -> q.getPayment().getId().equals(refund.getPayment().getId())
                && sameReference(q.getGatewayRefundId(), refund.getGatewayRefundId()));
            boolean linkedReturn = returns.stream().anyMatch(rc -> sameReference(rc.getSettlementPaymentId(), refund.getGatewayRefundId())
                || requests.stream().anyMatch(q -> q.getReturnCase() != null && q.getReturnCase().getId().equals(rc.getId())
                    && q.getPayment().getId().equals(refund.getPayment().getId()) && sameReference(q.getGatewayRefundId(), refund.getGatewayRefundId())));
            boolean completed = refund.getStatus() == RefundStatus.COMPLETED;
            Instant at = refund.getExecutedAt() != null ? refund.getExecutedAt() : refund.getUpdatedAt() != null ? refund.getUpdatedAt() : refund.getCreatedAt();
            if (completed && !linkedReturn && validAmount(refund.getAmount()) && at != null)
                events.add(new Event("refund:" + refund.getId(), "REFUND", refund.getAmount(), at, refund.getGatewayRefundId()));
            if (!linkedRequest && !linkedReturn && Set.of(RefundStatus.PENDING, RefundStatus.PROCESSING).contains(refund.getStatus()))
                pending = pending.add(positiveMoney(refund.getAmount()));
            if (!linkedRequest && !linkedReturn) {
                var p = refund.getPayment();
                var reservation = p.getReservation() != null ? p.getReservation() : p.getRenewal().getRental().getReservation();
                lines.add(new RefundLine(refund.getId(), reservation.getReservationCode(), reservation.getCustomerName(),
                    refund.getStatus().name(), refund.getReason(), null, null, null, refund.getAmount(), completed ? at : null, refund.getGatewayRefundId(), completed, null));
            }
        }
        BigDecimal deposit = BigDecimal.ZERO;
        var rentals = em.createQuery("select r from Rental r where r.facility.id=:facility", Rental.class).setParameter("facility", facilityId).getResultList();
        boolean depositUnknown = false;
        for (Rental rental : rentals) {
            if (settledRentals.contains(rental.getId())) continue;
            var snapshots = em.createQuery("select s from ReservationPricingSnapshot s where s.reservation.id=:id", ReservationPricingSnapshot.class)
                .setParameter("id", rental.getReservation().getId()).getResultList();
            if (snapshots.size() != 1) { depositUnknown = true; continue; }
            var pricing = snapshots.getFirst();
            var balances = recorded.values().stream().filter(p -> p.getReservation() != null
                && p.getReservation().getId().equals(rental.getReservation().getId()) && p.getPurpose() == PaymentType.RENTAL_BALANCE).toList();
            if (balances.size() != 1 || pricing.getSecurityDepositAmount() == null || pricing.getSecurityDepositAmount().signum() < 0 || pricing.getDueAtCheckIn() == null
                || pricing.getRemainingRentalAmount() == null || balances.getFirst().getAmount().compareTo(pricing.getDueAtCheckIn()) != 0
                || money(balances.getFirst().getRefundedAmount()).signum() > 0
                || pricing.getRemainingRentalAmount().add(pricing.getSecurityDepositAmount()).compareTo(pricing.getDueAtCheckIn()) != 0) {
                depositUnknown = true; continue;
            }
            deposit = deposit.add(pricing.getSecurityDepositAmount());
        }
        if (depositUnknown) warnings.add("Có hồ sơ chưa ghi đủ phân bổ cọc trong báo giá hoặc khoản thu nhận kho.");
        var daily = aggregateDaily(events, first, last);
        var monthly = aggregate(events, first, last);
        BigDecimal collected = monthly.stream().map(Month::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunded = monthly.stream().map(Month::refunded).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(facilityId, first, last, now, warnings.isEmpty() ? "RECORDED" : "PARTIAL", collected, refunded,
            collected.subtract(refunded), pending, pendingApproval, depositUnknown ? null : deposit, daily, monthly,
            List.copyOf(lines), List.copyOf(warnings), firstPaymentDate);
    }
    private static Instant paymentTimestamp(Payment payment) {
        return payment.getPaidAt() != null ? payment.getPaidAt()
            : payment.getProcessedAt() != null ? payment.getProcessedAt() : payment.getCreatedAt();
    }
    private static boolean sameReference(String a, String b) { return a != null && !a.isBlank() && a.equals(b); }
    private static boolean validAmount(BigDecimal amount) { return amount != null && amount.signum() >= 0; }
    static BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal positiveMoney(BigDecimal value) { return validAmount(value) && value.signum() > 0 ? value : BigDecimal.ZERO; }
    private static boolean pendingApprovalStatus(ReturnCaseStatus status) {
        return Set.of(
            ReturnCaseStatus.requested,
            ReturnCaseStatus.scheduled,
            ReturnCaseStatus.inspected,
            ReturnCaseStatus.awaiting_business_review,
            ReturnCaseStatus.awaiting_customer_confirmation,
            ReturnCaseStatus.disputed
        ).contains(status);
    }
    public static List<Day> aggregateDaily(List<Event> events, LocalDate from, LocalDate to) {
        var days = new LinkedHashMap<LocalDate, BigDecimal[]>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
            days.put(day, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        var ids = new HashSet<String>();
        for (Event event : events) {
            if (event.occurredAt() == null || event.amount() == null || event.amount().signum() < 0 || !ids.add(event.id())) continue;
            LocalDate day = event.occurredAt().atZone(ZONE).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            var sums = days.get(day);
            int index = "REFUND".equals(event.direction()) ? 1 : 0;
            sums[index] = sums[index].add(event.amount());
        }
        return days.entrySet().stream()
            .map(e -> new Day(e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[0].subtract(e.getValue()[1])))
            .toList();
    }
    public static List<Month> aggregate(List<Event> events, LocalDate from, LocalDate to) {
        var months = new LinkedHashMap<YearMonth, BigDecimal[]>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1))
            months.put(m, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        var ids = new HashSet<String>();
        for (Event event : events) {
            if (event.occurredAt() == null || event.amount() == null || event.amount().signum() < 0 || !ids.add(event.id())) continue;
            LocalDate day = event.occurredAt().atZone(ZONE).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            var sums = months.get(YearMonth.from(day));
            int index = "REFUND".equals(event.direction()) ? 1 : 0;
            sums[index] = sums[index].add(event.amount());
        }
        return months.entrySet().stream().map(e -> new Month(e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[0].subtract(e.getValue()[1]))).toList();
    }
}
