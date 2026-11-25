package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.EmailProperties;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.email.TransactionalEmailService;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import com.storagehub.time.BusinessTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowSlaScheduler {
    private static final Duration TWO_HOURS = Duration.ofHours(2);
    private static final Duration FOUR_HOURS = Duration.ofHours(4);
    private static final List<PaymentStatus> OPEN_RENEWAL_PAYMENT =
        List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.NOT_RECEIVED);
    private static final DateTimeFormatter LOCAL_TIME =
        DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(BusinessTime.ZONE);

    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditLogService audit;
    private final TransactionalEmailService mail;
    private final EmailProperties emailProperties;
    private final WorkflowBusinessCalendar calendar;
    private final WorkflowSlaMetrics metrics;

    private record Deadline(String workflow, BaseEntity resource, UUID facility, Instant startedAt, Instant dueAt,
        Duration warningLead, RoleCode owner, RoleCode escalation, UUID customer,
        NotificationType type, String description) {}

    public record WorkItem(String workflow, UUID resourceId, String facility, String description,
        Instant dueAt, String status, RoleCode waitingOn, boolean canResolve) {}

    @Scheduled(initialDelayString = "${app.workflow-sla.initial-delay-ms:60000}",
        fixedDelayString = "${app.workflow-sla.delay-ms:60000}")
    public void scan() {
        Instant now = Instant.now();
        deliverCustomerReminders(now);
        visitDeadlines(now, true, deadline -> deliver(deadline, now));
        transactions.executeWithoutResult(transaction -> {
            metrics.lockScan();
            List<WorkflowSlaMetrics.Observation> active = new ArrayList<>();
            visitDeadlines(now, false, deadline -> {
                if (!(deadline.resource() instanceof Reservation reservation && reservation.getStatus() == ReservationStatus.EXPIRED)) {
                    active.add(new WorkflowSlaMetrics.Observation(deadline.workflow(), deadline.resource().getId(),
                        deadline.facility(), deadline.owner(), deadline.startedAt(), deadline.dueAt()));
                }
            });
            metrics.observe(active, now);
        });
    }

    private void deliverCustomerReminders(Instant now) {
        var removalIds = em.createQuery("""
            select r.id from Rental r where r.inventoryRemovalDeadlineAt > :now
              and r.inventoryRemovalDeadlineAt <= :windowEnd and r.inventoryPending = true
              and r.inventoryEscalatedAt is null and r.actualReturnedAt is null
            """, UUID.class).setParameter("now", now).setParameter("windowEnd", now.plus(Duration.ofHours(48)))
            .getResultList();
        process(removalIds, Rental.class, rental -> {
            if (rental.getActualReturnedAt() != null || rental.getCompletedAt() != null) return null;
            boolean handedBack = em.createQuery("""
                select count(r) from ReturnCase r where r.rental.id = :id and r.returnType = :type
                  and r.readyForInspectionAt is not null
                """, Long.class).setParameter("id", rental.getId())
                .setParameter("type", ReturnType.INVENTORY_MISMATCH).getSingleResult() > 0;
            if (handedBack) return null;
            var deadline = inventoryDeadline(rental);
            return deadline != null && deadline.workflow().equals("INVENTORY_REMOVAL") ? deadline : null;
        }, true, deadline -> {
            Instant due = deadline.dueAt();
            if (!now.isBefore(due) || now.isBefore(due.minus(Duration.ofHours(48)))) return;
            boolean finalDay = !now.isBefore(due.minus(Duration.ofHours(24)));
            var rental = (Rental) deadline.resource();
            if (deliverCustomerReminder(deadline, finalDay ? "REMINDER_24H" : "REMINDER_48H", now,
                "Nhắc hạn trả kho do kiểm kê sai lệch",
                "Hạn đưa hàng ra khỏi kho là " + LOCAL_TIME.format(due)
                    + " (giờ Việt Nam). Vui lòng kiểm tra lịch trả kho hoặc liên hệ cơ sở để được hỗ trợ.",
                rental.getReservation().getId())) {
                rental.setInventoryReturnLastReminderAt(now);
            }
        });

        var renewalIds = em.createQuery("""
            select w.renewal.id from RenewalWorkflow w where w.paymentDeadline > :now
              and w.paymentDeadline <= :windowEnd and w.renewal.status in :statuses
            """, UUID.class).setParameter("now", now).setParameter("windowEnd", now.plus(Duration.ofMinutes(5)))
            .setParameter("statuses", List.of(RenewalStatus.accepted, RenewalStatus.approved)).getResultList();
        process(renewalIds, Renewal.class, renewal -> renewalPaymentReminder(renewal, now), true,
            deadline -> deliverCustomerReminder(deadline, "REMINDER_5M", now, "Sắp hết hạn thanh toán gia hạn",
                "Vui lòng hoàn tất thanh toán trước " + LOCAL_TIME.format(deadline.dueAt())
                    + " (giờ Việt Nam) để tiếp tục hồ sơ gia hạn.", deadline.resource().getId()));

        process(mismatchFinancialIds(), ReturnCase.class, this::mismatchFinancialDeadline, true, deadline -> {
            if (deadline.owner() != RoleCode.CUSTOMER || !now.isBefore(deadline.dueAt())) return;
            boolean payment = deadline.workflow().equals("MISMATCH_RETURN_PAYMENT");
            Instant first = payment ? InventoryTimelinePolicy.dayStart(deadline.startedAt(), 3)
                : deadline.startedAt().plus(Duration.ofHours(24));
            Instant second = payment ? InventoryTimelinePolicy.dayStart(deadline.startedAt(), 6)
                : deadline.startedAt().plus(Duration.ofHours(48));
            if (now.isBefore(first)) return;
            deliverCustomerReminder(deadline, now.isBefore(second) ? "REMINDER_1" : "REMINDER_2", now,
                payment ? "Nhắc thanh toán quyết toán trả kho" : "Nhắc xem bảng kê trả kho",
                "Vui lòng " + deadline.description() + " trước " + LOCAL_TIME.format(deadline.dueAt())
                    + " (giờ Việt Nam).", deadline.resource().getId());
        });
    }

    private List<UUID> mismatchFinancialIds() {
        return em.createQuery("""
            select r.id from ReturnCase r where r.returnType = :type and r.status in :statuses
            """, UUID.class).setParameter("type", ReturnType.INVENTORY_MISMATCH)
            .setParameter("statuses", List.of(ReturnCaseStatus.awaiting_customer_confirmation, ReturnCaseStatus.disputed,
                ReturnCaseStatus.payment_due, ReturnCaseStatus.refund_pending)).getResultList();
    }

    private Deadline mismatchFinancialDeadline(ReturnCase rc) {
        if (rc.getReturnType() != ReturnType.INVENTORY_MISMATCH) return null;
        Instant reviewed = rc.getReviewedAt() == null ? rc.getInspectedAt() : rc.getReviewedAt();
        Instant start;
        Instant due;
        String workflow;
        String description;
        RoleCode owner;
        RoleCode escalation = RoleCode.BUSINESS;
        switch (rc.getStatus()) {
            case awaiting_customer_confirmation -> {
                if (rc.isCustomerConfirmed()) return null;
                start = rc.getSettlementPublishedAt() == null ? reviewed : rc.getSettlementPublishedAt();
                if (start == null) return null;
                due = start.plus(Duration.ofHours(72));
                workflow = "MISMATCH_RETURN_CONFIRMATION";
                description = "phản hồi bảng kê trả kho";
                owner = RoleCode.CUSTOMER;
            }
            case disputed -> {
                start = rc.getCustomerConfirmedAt();
                if (start == null) return null;
                due = start.plus(Duration.ofHours(48));
                workflow = "MISMATCH_RETURN_DISPUTE";
                description = "đối soát khiếu nại trả kho";
                owner = RoleCode.MANAGER;
            }
            case payment_due -> {
                start = rc.getCustomerConfirmedAt() == null || reviewed != null && reviewed.isAfter(rc.getCustomerConfirmedAt())
                    ? reviewed : rc.getCustomerConfirmedAt();
                if (start == null) return null;
                due = rc.getSettlementPaymentDueAt() == null ? InventoryTimelinePolicy.dayStart(start, 8) : rc.getSettlementPaymentDueAt();
                workflow = "MISMATCH_RETURN_PAYMENT";
                description = "thanh toán công nợ trả kho";
                owner = RoleCode.CUSTOMER;
            }
            case refund_pending -> {
                due = rc.getRefundExpectedAt();
                if (due == null) return null;
                start = due.minus(Duration.ofDays(7));
                workflow = "MISMATCH_RETURN_REFUND";
                description = "hoàn tiền quyết toán trả kho";
                owner = RoleCode.BUSINESS;
                escalation = RoleCode.ADMIN;
            }
            default -> { return null; }
        }
        return new Deadline(workflow, rc, rc.getFacility().getId(), start, due,
            owner == RoleCode.CUSTOMER ? Duration.ZERO : TWO_HOURS,
            owner, escalation, rc.getCustomer().getId(), NotificationType.RETURN, description);
    }

    private Deadline renewalPaymentReminder(Renewal renewal, Instant now) {
        if (renewal.getStatus() != RenewalStatus.accepted && renewal.getStatus() != RenewalStatus.approved) return null;
        var workflow = em.find(RenewalWorkflow.class, renewal.getId());
        if (workflow == null || workflow.getCancellationReason() != null) return null;
        Instant due = workflow.getPaymentDeadline();
        if (due == null || !now.isBefore(due) || due.isAfter(now.plus(Duration.ofMinutes(5)))) return null;
        var state = em.find(RenewalOperationState.class, renewal.getId());
        if (state != null && (state.hasVerifiedPayment()
            || state.getPhase() != RenewalOperationState.Phase.AWAITING_PAYMENT_INTENT)) return null;
        boolean paidOrReconciling = em.createQuery("""
            select count(p) from Payment p where p.renewal.id = :id and p.status in :statuses
            """, Long.class).setParameter("id", renewal.getId())
            .setParameter("statuses", List.of(PaymentStatus.PAID, PaymentStatus.PROCESSING, PaymentStatus.NOT_RECEIVED))
            .getSingleResult() > 0;
        if (paidOrReconciling) return null;
        var rental = renewal.getRental();
        return new Deadline("RENEWAL_PAYMENT_REMINDER", renewal, rental.getFacility().getId(), renewal.getCreatedAt(),
            due, Duration.ofMinutes(5), RoleCode.CUSTOMER, RoleCode.MANAGER, rental.getCustomer().getId(),
            NotificationType.RENEWAL, "thanh toán gia hạn");
    }

    private boolean deliverCustomerReminder(Deadline deadline, String milestone, Instant now, String title,
        String content, UUID relatedId) {
        if (!claim(deadline, milestone, deadline.customer(), now)) return false;
        notifications.createNotification(deadline.customer(), deadline.type(), title, content, relatedId);
        return true;
    }

    @Transactional(readOnly = true)
    public List<WorkItem> list(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Chỉ tài khoản nội bộ được xem hạn xử lý.");
        Instant now = Instant.now();
        List<WorkItem> rows = new ArrayList<>();
        visitDeadlines(now, false, deadline -> {
            if (!canView(actor, deadline.facility())) return;
            boolean overdue = !now.isBefore(deadline.dueAt());
            boolean warning = !now.isBefore(deadline.dueAt().minus(deadline.warningLead()));
            if (deadline.resource() instanceof Reservation reservation && reservation.getStatus() == ReservationStatus.EXPIRED) return;
            String facility = em.getReference(Facility.class, deadline.facility()).getName();
            rows.add(new WorkItem(deadline.workflow(), deadline.resource().getId(), facility, deadline.description(),
                deadline.dueAt(), overdue ? "OVERDUE" : warning ? "WARNING" : "OPEN", deadline.owner(),
                !deadline.workflow().equals("INVENTORY_REMOVAL") && (deadline.workflow().equals("CHECKIN_REJECTION")
                    || deadline.resource() instanceof Rental rental && rental.getInventoryEscalatedAt() == null)
                    && canResolve(actor, deadline.facility())));
        });
        rows.sort(Comparator.comparing(WorkItem::dueAt).thenComparing(WorkItem::resourceId));
        return rows;
    }

    @Transactional
    public void resolveRejection(ActorPrincipal actor, UUID id, String note) {
        CheckIn checkIn = em.find(CheckIn.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (checkIn == null || !canResolve(actor, checkIn.getReservation().getFacility().getId()))
            throw ApiExceptions.notFound("Không tìm thấy hồ sơ từ chối bàn giao trong phạm vi quản lý.");
        if (checkIn.getStatus() != CheckInStatus.rejected || checkIn.getRejectionResolvedAt() != null)
            throw ApiExceptions.conflict("Hồ sơ không còn chờ xử lý sau từ chối.");
        String resolution = note == null ? "" : note.trim();
        if (resolution.isEmpty() || resolution.length() > 1000)
            throw ApiExceptions.validation("Nhập kết quả xử lý từ 1 đến 1000 ký tự.", null);
        checkIn.setRejectionResolvedAt(Instant.now());
        checkIn.setRejectionResolutionNote(resolution);
        audit.recordMutation("CHECKIN_REJECTION_FOLLOWUP_RESOLVED", "CheckIn", id,
            checkIn.getReservation().getFacility().getId(), null, Map.of("note", resolution));
    }

    @Transactional
    public void extendInventoryDeadline(
        ActorPrincipal actor, UUID id, String workflow, Instant previousDeadline, Instant newDeadline, String note
    ) {
        Rental rental = em.find(Rental.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (rental == null || !canResolve(actor, rental.getFacility().getId())) {
            throw ApiExceptions.notFound("Không tìm thấy hồ sơ trong phạm vi quản lý.");
        }
        if (rental.getInventoryRemovalDeadlineAt() != null) {
            throw ApiExceptions.conflict("Hạn trả kho do sai lệch chỉ được gia hạn theo chính sách cỡ kho. Trường hợp ngoại lệ cần đối soát.");
        }
        Deadline deadline = inventoryDeadline(rental);
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty() || reason.length() > 1000) {
            throw ApiExceptions.validation("Nhập lý do chậm do cơ sở từ 1 đến 1000 ký tự.", null);
        }
        if (deadline == null || rental.getInventoryEscalatedAt() != null) {
            throw ApiExceptions.conflict("Hồ sơ đã chuyển sang thu hồi hoặc đã xử lý xong.");
        }
        if (!deadline.workflow().equals(workflow) || !deadline.dueAt().equals(previousDeadline)) {
            throw ApiExceptions.conflict("Hạn xử lý đã thay đổi. Vui lòng tải lại danh sách trước khi gia hạn.");
        }
        if (newDeadline == null || !newDeadline.isAfter(deadline.dueAt()) || !newDeadline.isAfter(Instant.now())) {
            throw ApiExceptions.validation("Hạn mới phải sau hạn hiện tại và thời điểm hiện tại.", null);
        }
        {
            rental.setInventoryFinalDeadlineAt(newDeadline);
            for (MaintenanceTask task : em.createQuery("""
                select t from MaintenanceTask t where t.rental.id = :id
                  and t.taskType = :type and t.status in :statuses
                """, MaintenanceTask.class).setParameter("id", id)
                .setParameter("type", MaintenanceTaskType.inventory_check)
                .setParameter("statuses", List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList()) {
                task.setDueAt(newDeadline.atZone(BusinessTime.ZONE).toLocalDate());
                task.setDeadlineAt(newDeadline);
            }
        }
        UUID facilityId = rental.getFacility().getId();
        audit.recordMutation("INVENTORY_DEADLINE_EXTENDED", "Rental", id, facilityId,
            Map.of("dueAt", deadline.dueAt()), Map.of("dueAt", newDeadline, "reason", reason,
                "approvedBy", actor.userId(), "workflow", deadline.workflow()));
        String title = "Đã gia hạn " + deadline.description();
        String content = "Hạn mới " + LOCAL_TIME.format(newDeadline) + " (giờ Việt Nam). Lý do: " + reason;
        UUID reservationId = rental.getReservation().getId();
        notifications.createNotification(rental.getCustomer().getId(), NotificationType.CHECKIN, title, content, reservationId);
        notifications.createFacilityOperationsNotifications(facilityId, NotificationType.CHECKIN, title, content, reservationId);
    }

    private boolean canView(ActorPrincipal actor, UUID facility) {
        var scope = actor.facilityScopes().get(facility);
        return actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN)
            || (actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER) && scope != null && scope.includes(FacilityScopeLevel.OPERATE));
    }

    private boolean canResolve(ActorPrincipal actor, UUID facility) {
        return actor.hasRole(RoleCode.MANAGER) && actor.facilityScopes().get(facility) == FacilityScopeLevel.MANAGE;
    }

    private void visitDeadlines(Instant now, boolean lock, Consumer<Deadline> visitor) {
        var schedule = calendar.current();
        List<UUID> reservations = em.createQuery("""
            select r.id from Reservation r where
              (r.goodsReviewStatus=:pending and (:dashboard=true or r.goodsReviewDueAt <= :warning)
                and (r.status=:review or (r.status=:expired and r.expiredAt >= :recent)))
              or (r.status=:confirmed and r.assignedUnit is null and (:dashboard=true or r.confirmedAt <= :assignmentWarning))
            """, UUID.class)
            .setParameter("pending", GoodsReviewStatus.PENDING)
            .setParameter("dashboard", !lock)
            .setParameter("review", ReservationStatus.AWAITING_REVIEW)
            .setParameter("expired", ReservationStatus.EXPIRED)
            .setParameter("confirmed", ReservationStatus.CONFIRMED)
            .setParameter("warning", now.plus(TWO_HOURS))
            .setParameter("recent", now.minus(Duration.ofDays(1)))
            .setParameter("assignmentWarning", now.minus(Duration.ofHours(ReservationCheckInPolicy.MANAGER_ASSIGNMENT_HOURS)).plus(TWO_HOURS))
            .getResultList();
        process(reservations, Reservation.class, this::reservationDeadline, lock, visitor);
        process(ids("select r.id from ReturnCase r where r.status in :statuses",
            List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled)), ReturnCase.class, item -> returnDeadline(item, schedule, now), lock, visitor);
        process(mismatchFinancialIds(), ReturnCase.class, this::mismatchFinancialDeadline, lock, visitor);
        process(ids("select r.id from ReturnCase r where r.status in :statuses and r.returnType = com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH",
            List.of(ReturnCaseStatus.awaiting_business_review)), ReturnCase.class, rc -> {
                if (rc.getStatus() != ReturnCaseStatus.awaiting_business_review || rc.getInspectedAt() == null) return null;
                Instant start = rc.getInspectedAt();
                return new Deadline("MISMATCH_RETURN_FINANCIAL_REVIEW", rc, rc.getFacility().getId(), start,
                    schedule.afterWorkingHours(start, 6), TWO_HOURS, RoleCode.BUSINESS, RoleCode.ADMIN, null,
                    NotificationType.RETURN, "duyệt bảng kê trả kho cần đối soát");
            }, lock, visitor);
        process(ids("""
            select t.id from MaintenanceTask t left join t.returnCase rc where t.status in :statuses and t.deadlineAt is not null
              and (rc.returnType = com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH
                or t.recoveryType = com.storagehub.domain.model.MaintenanceRecoveryType.MISMATCH_REMOVAL)
            """, List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)),
            MaintenanceTask.class, this::mismatchMaintenanceDeadline, lock, visitor);
        if (!lock) {
            process(ids("""
                select r.id from Rental r where r.status in :statuses and r.inventoryPending = true
                  and r.inventoryEscalatedAt is null
                  and (r.inventoryFinalDeadlineAt is not null or r.inventoryRemovalDeadlineAt is not null)
                """, List.of(RentalStatus.setup, RentalStatus.active, RentalStatus.return_requested)),
                Rental.class, this::inventoryDeadline, false, visitor);
        }
        process(ids("select f.id from Facility f where f.status in :statuses", List.of(FacilityStatus.setup)),
            Facility.class, item -> facilityDeadline(item, schedule), lock, visitor);
        process(ids("""
            select u.id from UnitType u where u.status in :statuses
              and (u.monthlyPrice is null or u.monthlyPrice <= 0)
              and u.facility.status in (com.storagehub.domain.model.FacilityStatus.setup, com.storagehub.domain.model.FacilityStatus.active)
            """, List.of(UnitTypeStatus.awaiting_pricing)), UnitType.class, item -> pricingDeadline(item, schedule), lock, visitor);
        process(ids("select c.id from UnitTypeChangeRequest c where c.status in :statuses and c.requiresRepricing=true",
            List.of("PENDING")), UnitTypeChangeRequest.class, item -> pricingReviewDeadline(item, schedule), lock, visitor);
        process(ids("""
            select c.id from CheckIn c where c.status in :statuses
              and (c.rejectionResolvedAt is null)
            """, List.of(CheckInStatus.scheduled, CheckInStatus.identity_verified, CheckInStatus.contract_signed, CheckInStatus.payment_collected, CheckInStatus.rejected, CheckInStatus.handover_pending_receipt)),
            CheckIn.class, checkIn -> checkInDeadline(checkIn, now, schedule), lock, visitor);
        var pendingRefusals = em.createQuery("""
            select c.id from CheckIn c join c.inventoryConfirmation confirmation
            where confirmation.inventoryRefusalSubmittedAt is not null and confirmation.inventoryRefusalApproved is null
            """, UUID.class).getResultList();
        process(pendingRefusals, CheckIn.class, checkIn -> {
            if (!checkIn.hasPendingInventoryRefusal()) return null;
            Instant start = checkIn.getInventoryConfirmation().getInventoryRefusalSubmittedAt();
            Instant due = schedule.afterWorkingHours(start, 4);
            return new Deadline("INVENTORY_SIGNATURE_REFUSAL", checkIn, checkIn.getReservation().getFacility().getId(),
                start, due, Duration.between(start, due), RoleCode.MANAGER, RoleCode.BUSINESS, null,
                NotificationType.CHECKIN, "duyệt biên bản khách từ chối ký");
        }, lock, visitor);
        var reviews = em.createQuery("""
            select p.id from Payment p where p.renewal.status=:closed and p.purpose=:purpose
              and p.paymentDeadline is not null
              and (p.status in :pending or (p.status=:paid and (p.refundedAmount is null or p.refundedAmount<p.amount)))
            """, UUID.class).setParameter("closed",RenewalStatus.payment_expired)
            .setParameter("purpose",PaymentType.RENEWAL).setParameter("pending",OPEN_RENEWAL_PAYMENT)
            .setParameter("paid",PaymentStatus.PAID).getResultList();
        process(reviews, Payment.class, payment -> reconciliationDeadline(payment, schedule), lock, visitor);
    }

    @org.springframework.beans.factory.annotation.Value("${storagehub.renewal.payment-reconciliation-minutes:30}")
    private long reconciliationMinutes = 30;

    private Deadline reconciliationDeadline(Payment payment, WorkflowBusinessCalendar.Schedule schedule) {
        var renewal = payment.getRenewal();
        if (renewal == null || renewal.getStatus()!=RenewalStatus.payment_expired || payment.getPurpose()!=PaymentType.RENEWAL
            || payment.getPaymentDeadline()==null) return null;
        boolean paidUnresolved = payment.getStatus()==PaymentStatus.PAID
            && (payment.getRefundedAmount()==null || payment.getRefundedAmount().compareTo(payment.getAmount())<0);
        if (!paidUnresolved && !OPEN_RENEWAL_PAYMENT.contains(payment.getStatus())) return null;
        Instant start = RenewalPaymentExpiryService.manualReviewAt(payment.getPaymentDeadline(), reconciliationMinutes);
        if (paidUnresolved && payment.getProcessedAt()!=null && payment.getProcessedAt().isBefore(start)) start=payment.getProcessedAt();
        return new Deadline("RENEWAL_PAYMENT_REVIEW", payment, renewal.getRental().getFacility().getId(),
            start, schedule.after(start,1), FOUR_HOURS, RoleCode.MANAGER, RoleCode.BUSINESS, null,
            NotificationType.PAYMENT, "đối soát giao dịch gia hạn " + payment.getGatewayIntentId());
    }

    private List<UUID> ids(String query, List<?> statuses) {
        return em.createQuery(query, UUID.class).setParameter("statuses", statuses).getResultList();
    }

    private <T extends BaseEntity> void process(List<UUID> ids, Class<T> entityType,
        Function<T, Deadline> deadlineFor, boolean lock, Consumer<Deadline> visitor) {
        for (UUID id : ids) {
            if (!lock) {
                T entity = em.find(entityType, id);
                if (entity != null) {
                    Deadline deadline = deadlineFor.apply(entity);
                    if (deadline != null) visitor.accept(deadline);
                }
                continue;
            }
            try {
                transactions.executeWithoutResult(transaction -> {
                    T entity = em.find(entityType, id, LockModeType.PESSIMISTIC_WRITE);
                    if (entity == null) return;
                    Deadline deadline = deadlineFor.apply(entity);
                    if (deadline != null) visitor.accept(deadline);
                });
            } catch (RuntimeException failure) {
                log.warn("Workflow SLA deferred for {} {}", entityType.getSimpleName(), id, failure);
            }
        }
    }

    private Deadline checkInDeadline(CheckIn checkIn, Instant now, WorkflowBusinessCalendar.Schedule schedule) {
        var reservation = checkIn.getReservation();
        UUID facility = reservation.getFacility().getId();
        String order = " của đơn " + reservation.getReservationCode();
        if (reservation.getStatus() == ReservationStatus.READY_FOR_CHECKIN && reservation.getFirstHandoverComparisonAt() == null
            && checkIn.getIdentityVerifiedAt() != null && java.util.EnumSet.of(CheckInStatus.identity_verified,
                CheckInStatus.contract_signed, CheckInStatus.payment_collected).contains(checkIn.getStatus())) {
            Instant verified = checkIn.getIdentityVerifiedAt();
            return new Deadline("CHECKIN_LEGACY_HANDOVER_REVIEW", checkIn, facility, verified, schedule.after(verified, 1),
                FOUR_HOURS, RoleCode.MANAGER, RoleCode.BUSINESS, null, NotificationType.CHECKIN,
                "rà soát hồ sơ bàn giao cũ chưa có mốc đối chiếu đầu tiên" + order);
        }
        if (checkIn.getStatus() == CheckInStatus.scheduled && reservation.getStatus() == ReservationStatus.READY_FOR_CHECKIN) {
            if (reservation.getArrivalReviewOpenedAt() != null) {
                Instant opened = reservation.getArrivalReviewOpenedAt();
                return new Deadline("CHECKIN_DISPUTED_ARRIVAL", checkIn, facility, opened, schedule.after(opened, 1),
                    FOUR_HOURS, RoleCode.MANAGER, RoleCode.BUSINESS, null, NotificationType.CHECKIN, "đối soát giờ đến" + order);
            }
            if (reservation.getHandoverArrivedAt() != null) {
                if (reservation.getFirstHandoverComparisonAt() != null) return null;
                Instant arrived = reservation.getHandoverArrivedAt();
                return new Deadline("CHECKIN_PENDING_COMPARISON", checkIn, facility, arrived, schedule.after(arrived, 1),
                    FOUR_HOURS, RoleCode.STAFF, RoleCode.MANAGER, null, NotificationType.CHECKIN,
                    "tiếp nhận và đối chiếu CCCD lần đầu" + order);
            }
            Instant appointment = reservation.getAppointmentAt() != null ? reservation.getAppointmentAt() : checkIn.getScheduledAt();
            if (appointment == null || now.isBefore(appointment)) return null;
            return new Deadline("CHECKIN_ATTENDANCE", checkIn, facility, appointment, schedule.after(appointment, 1),
                FOUR_HOURS, RoleCode.STAFF, RoleCode.MANAGER, null, NotificationType.CHECKIN, "xác nhận khách đến hoặc vắng mặt" + order);
        }
        if (checkIn.getStatus() == CheckInStatus.rejected && checkIn.getRejectionResolvedAt() == null && checkIn.getCheckedInAt() != null) {
            return new Deadline("CHECKIN_REJECTION", checkIn, facility, checkIn.getCheckedInAt(), schedule.after(checkIn.getCheckedInAt(), 1),
                FOUR_HOURS, RoleCode.MANAGER, RoleCode.BUSINESS, null, NotificationType.CHECKIN, "xử lý sau khi từ chối bàn giao" + order);
        }
        if (checkIn.getStatus() == CheckInStatus.handover_pending_receipt
            && reservation.getStatus() == ReservationStatus.AWAITING_CUSTOMER_RECEIPT && checkIn.getCheckedInAt() != null) {
            return new Deadline("CUSTOMER_RECEIPT", checkIn, facility, checkIn.getCheckedInAt(),
                ReservationCheckInPolicy.customerReceiptConfirmationDueAt(checkIn.getCheckedInAt()),
                Duration.ofHours(ReservationCheckInPolicy.CUSTOMER_RECEIPT_REMINDER_HOURS),
                RoleCode.CUSTOMER, RoleCode.MANAGER, reservation.getCustomer().getId(), NotificationType.CHECKIN, "chờ khách xác nhận nhận kho" + order);
        }
        return null;
    }

    private Deadline reservationDeadline(Reservation reservation) {
        UUID facility = reservation.getFacility().getId();
        if (reservation.getGoodsReviewStatus() == GoodsReviewStatus.PENDING && reservation.getGoodsReviewDueAt() != null
            && (reservation.getStatus() == ReservationStatus.AWAITING_REVIEW
                || (reservation.getStatus() == ReservationStatus.EXPIRED && reservation.getExpiredAt() != null
                    && !reservation.getExpiredAt().isBefore(reservation.getGoodsReviewDueAt())))) {
            return new Deadline("GOODS_REVIEW", reservation, facility,
                reservation.getGoodsReviewSubmittedAt() == null ? reservation.getCreatedAt() : reservation.getGoodsReviewSubmittedAt(),
                reservation.getGoodsReviewDueAt(), TWO_HOURS,
                RoleCode.STAFF, RoleCode.MANAGER, null, NotificationType.RESERVATION, "duyệt hàng khác của đơn " + reservation.getReservationCode());
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED || reservation.getAssignedUnit() != null
            || reservation.getConfirmedAt() == null) return null;
        return new Deadline("UNIT_ASSIGNMENT", reservation, facility, reservation.getConfirmedAt(),
            ReservationCheckInPolicy.managerAssignmentDueAt(reservation.getConfirmedAt()), TWO_HOURS,
            RoleCode.MANAGER, RoleCode.BUSINESS, reservation.getCustomer().getId(), NotificationType.RESERVATION,
            "phân gian kho cho đơn " + reservation.getReservationCode());
    }

    private Deadline returnDeadline(ReturnCase returned, WorkflowBusinessCalendar.Schedule schedule, Instant now) {
        if (!Set.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(returned.getStatus())
            || returned.getInspectedAt() != null) return null;
        Instant review = ReturnAttendancePolicy.reviewStartsAt(returned);
        if (review != null && !now.isBefore(review)) {
            Instant due = schedule.afterWorkingHours(review, 4);
            boolean mismatch = ReturnAttendancePolicy.isDeadlineMismatchReturn(returned);
            return new Deadline(mismatch ? "MISMATCH_RETURN_ARRIVAL_REVIEW" : "RETURN_ARRIVAL_REVIEW",
                returned, returned.getFacility().getId(), review,
                due, Duration.between(review, due), RoleCode.MANAGER, RoleCode.BUSINESS, null,
                NotificationType.RETURN, mismatch ? "đối soát phiên xác minh trả kho do sai lệch" : "đối soát phiên xác minh trả kho");
        }
        Instant start = returned.getRequestedAt();
        if (returned.getReturnType() == ReturnType.INVENTORY_MISMATCH && returned.getReadyForInspectionAt() != null) {
            start = returned.getReadyForInspectionAt();
            return new Deadline("RETURN_INSPECTION", returned, returned.getFacility().getId(), start,
                schedule.afterWorkingHours(start, 4), TWO_HOURS, RoleCode.STAFF, RoleCode.MANAGER, null,
                NotificationType.RETURN, "nghiệm thu gian đã bàn giao");
        }
        if (returned.getScheduledDate() != null) {
            Instant appointmentDay = returned.getScheduledDate().atStartOfDay(BusinessTime.ZONE).toInstant();
            if (appointmentDay.isAfter(start)) start = appointmentDay;
        }
        return new Deadline("RETURN_INSPECTION", returned, returned.getFacility().getId(), start,
            schedule.after(start, 1), FOUR_HOURS, RoleCode.STAFF, RoleCode.MANAGER, null,
            NotificationType.RETURN, "kiểm tra yêu cầu trả kho");
    }

    private Deadline mismatchMaintenanceDeadline(MaintenanceTask task) {
        if (!List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress).contains(task.getStatus())
            || task.getDeadlineAt() == null) return null;
        boolean release = MismatchUnitRelease.isReleaseTask(task);
        if (!release && task.getRecoveryType() != com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW
            && task.getRecoveryType() != com.storagehub.domain.model.MaintenanceRecoveryType.MISMATCH_REMOVAL) return null;
        boolean assigned = task.getAssignedTo() != null;
        return new Deadline(release ? "MISMATCH_UNIT_READINESS" : "MISMATCH_GOODS_RECOVERY", task,
            task.getFacility().getId(), task.getCreatedAt(), task.getDeadlineAt(), TWO_HOURS,
            assigned ? RoleCode.STAFF : RoleCode.MANAGER, assigned ? RoleCode.MANAGER : RoleCode.BUSINESS,
            null, NotificationType.RETURN, release ? "kiểm tra gian để mở lại" : "thu hồi hàng do sai lệch");
    }

    private Deadline inventoryDeadline(Rental rental) {
        if (!rental.isInventoryPending() || rental.getInventoryEscalatedAt() != null) return null;
        boolean removal = rental.getInventoryRemovalDeadlineAt() != null;
        if (rental.getStatus() == RentalStatus.return_requested) {
            if (!removal) return null;
            Long open = em.createQuery("""
                select count(r) from ReturnCase r where r.rental.id = :id and r.returnType = :type
                  and r.status in :statuses and r.inspectedAt is null
                """, Long.class).setParameter("id", rental.getId()).setParameter("type", ReturnType.INVENTORY_MISMATCH)
                .setParameter("statuses", List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled)).getSingleResult();
            if (open == 0) return null;
        } else if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            return null;
        }
        Instant start = removal ? rental.getInventoryRemovalPublishedAt() : rental.getMoveInCompletedAt();
        Instant due = removal ? rental.getInventoryRemovalDeadlineAt() : rental.getInventoryFinalDeadlineAt();
        if (start == null || due == null) return null;
        Duration warning = !removal && rental.getInventoryDueAt() != null
            ? Duration.between(rental.getInventoryDueAt(), due) : TWO_HOURS;
        return new Deadline(removal ? "INVENTORY_REMOVAL" : "INVENTORY_CHECK", rental, rental.getFacility().getId(),
            start, due, warning, removal ? RoleCode.CUSTOMER : RoleCode.STAFF, RoleCode.MANAGER,
            rental.getCustomer().getId(), NotificationType.CHECKIN,
            (removal ? "đưa hàng sai lệch ra khỏi kho" : "kiểm kê đầu vào") + " · " + rental.getReservation().getReservationCode());
    }

    private Deadline facilityDeadline(Facility facility, WorkflowBusinessCalendar.Schedule schedule) {
        if (facility.getStatus() != FacilityStatus.setup) return null;
        Instant assignedAt = em.createQuery("""
            select min(s.createdAt) from UserFacilityScope s join s.user.roles role
              where s.facility.id=:facility and s.user.status=:active and role.code=:role and s.scopeLevel=:scope
            """, Instant.class).setParameter("facility", facility.getId()).setParameter("active", UserStatus.ACTIVE)
            .setParameter("role", RoleCode.MANAGER).setParameter("scope", FacilityScopeLevel.MANAGE).getSingleResult();
        if (assignedAt == null) {
            return new Deadline("FACILITY_MANAGER", facility, facility.getId(), facility.getCreatedAt(), schedule.after(facility.getCreatedAt(), 1),
                FOUR_HOURS, RoleCode.ADMIN, RoleCode.BUSINESS, null, NotificationType.SYSTEM,
                "tạo hoặc gán Manager cho cơ sở " + facility.getName());
        }
        List<UUID> configuredTypes = em.createQuery("""
            select t.id from UnitType t where t.facility.id=:facility and t.status in :ready
            """, UUID.class).setParameter("facility", facility.getId())
            .setParameter("ready", List.of(UnitTypeStatus.awaiting_pricing, UnitTypeStatus.active))
            .getResultList();
        if (!configuredTypes.isEmpty()) {
            long readyTypes = em.createQuery("""
                select count(distinct u.unitType.id) from StorageUnit u
                  where u.facility.id=:facility and u.unitType.id in :types and u.status=:available
                """, Long.class).setParameter("facility", facility.getId()).setParameter("types", configuredTypes)
                .setParameter("available", StorageUnitStatus.available).getSingleResult();
            if (readyTypes == configuredTypes.size()) return null;
        }
        return new Deadline("FACILITY_SETUP", facility, facility.getId(), assignedAt, schedule.after(assignedAt, 2),
            FOUR_HOURS, RoleCode.MANAGER, RoleCode.BUSINESS, null, NotificationType.SYSTEM,
            "cấu hình loại kho và gian khả dụng cho cơ sở " + facility.getName());
    }

    private Deadline pricingDeadline(UnitType unitType, WorkflowBusinessCalendar.Schedule schedule) {
        if (unitType.getStatus() != UnitTypeStatus.awaiting_pricing
            || !Set.of(FacilityStatus.setup, FacilityStatus.active).contains(unitType.getFacility().getStatus())
            || (unitType.getMonthlyPrice() != null && unitType.getMonthlyPrice().signum() > 0)) return null;
        return new Deadline("UNIT_TYPE_PRICING", unitType, unitType.getFacility().getId(), unitType.getCreatedAt(),
            schedule.after(unitType.getCreatedAt(), 1), FOUR_HOURS, RoleCode.BUSINESS, RoleCode.ADMIN, null,
            NotificationType.SYSTEM, "định giá loại kho " + unitType.getCode());
    }

    private Deadline pricingReviewDeadline(UnitTypeChangeRequest change, WorkflowBusinessCalendar.Schedule schedule) {
        if (!"PENDING".equals(change.getStatus()) || !change.isRequiresRepricing()) return null;
        return new Deadline("REPRICING_REVIEW", change, change.getFacilityId(), change.getCreatedAt(),
            schedule.after(change.getCreatedAt(), 1), FOUR_HOURS, RoleCode.BUSINESS, RoleCode.ADMIN, null,
            NotificationType.SYSTEM, "duyệt và định giá lại loại kho " + change.getUnitTypeCode());
    }

    private void deliver(Deadline deadline, Instant now) {
        if (now.isBefore(deadline.dueAt().minus(deadline.warningLead()))) return;
        boolean overdue = !now.isBefore(deadline.dueAt());
        String milestone = overdue ? "OVERDUE" : "WARNING";
        boolean receipt = deadline.workflow().equals("CUSTOMER_RECEIPT");
        Set<UUID> recipients = new LinkedHashSet<>(recipients(receipt ? RoleCode.MANAGER : deadline.owner(), deadline.facility()));
        if (deadline.resource() instanceof MaintenanceTask task && task.getAssignedTo() != null) {
            recipients.retainAll(Set.of(task.getAssignedTo().getId()));
            if (recipients.isEmpty()) recipients.addAll(recipients(RoleCode.MANAGER, deadline.facility()));
        }
        if (overdue) {
            recipients.addAll(recipients(deadline.escalation(), deadline.facility()));
            if (!receipt && deadline.customer() != null) recipients.add(deadline.customer());
        }
        String title = (overdue ? "Quá hạn " : "Sắp đến hạn ") + deadline.description();
        if (title.length() > 200) title = title.substring(0, 200);
        String content = "Hạn xử lý " + LOCAL_TIME.format(deadline.dueAt()) + " (giờ Việt Nam). "
            + (overdue ? "Công việc đã được báo cho bên phụ trách." : "Vui lòng hoàn tất trước hạn.");
        int sent = 0;
        for (UUID recipient : recipients) {
            if (!claim(deadline, milestone, recipient, now)) continue;
            if (!receipt) notifications.createNotification(recipient, deadline.type(), title, content, notificationResource(deadline));
            if (!recipient.equals(deadline.customer())) queueEmail(deadline, milestone, recipient, title, content, overdue);
            sent++;
        }
        if (sent > 0) {
            audit.recordMutation((User) null, "WORKFLOW_SLA_" + milestone, deadline.resource().getClass().getSimpleName(),
                deadline.resource().getId(), deadline.facility(), null, Map.of(
                    "workflow", deadline.workflow(), "dueAt", deadline.dueAt(), "recipientCount", sent,
                    "waitingOn", deadline.owner().name(),
                    "waitingSide", deadline.owner() == RoleCode.CUSTOMER ? "CUSTOMER" : "OPERATIONS"));
        }
    }

    private UUID notificationResource(Deadline deadline) {
        if (deadline.resource() instanceof CheckIn checkIn) return checkIn.getReservation().getId();
        return deadline.resource().getId();
    }

    private void queueEmail(Deadline deadline, String milestone, UUID recipient, String title, String content, boolean overdue) {
        User user = em.find(User.class, recipient);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        var variables = new java.util.LinkedHashMap<String, Object>();
        variables.put("title", title);
        variables.put("summary", content);
        variables.put("fullName", user.getFullName());
        variables.put("details", Map.of("Công việc", deadline.description(), "Hạn xử lý", LOCAL_TIME.format(deadline.dueAt())));
        variables.put("detailsUrl", emailProperties.getFrontendBaseUrl().replaceAll("/+$", "") + "/login");
        if (!overdue) variables.put("_expiresAt", deadline.dueAt().toString());
        String key = "workflow-sla:" + deadline.workflow() + ":" + deadline.resource().getId()
            + ":" + deadline.dueAt() + ":" + milestone;
        mail.queueTemplate(recipient, user.getEmail(), "StorageHub - " + title, "customer-lifecycle",
            variables, "WORKFLOW_SLA_EMAIL_SENT", key);
    }

    // The caller holds the resource row lock. The unique key also prevents duplicate delivery.
    private boolean claim(Deadline deadline, String milestone, UUID recipient, Instant now) {
        byte[] resource = uuidBytes(deadline.resource().getId());
        byte[] user = uuidBytes(recipient);
        Timestamp due = Timestamp.from(deadline.dueAt().truncatedTo(ChronoUnit.MICROS));
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM workflow_sla_alerts
              WHERE workflow_type=? AND resource_id=? AND due_at=? AND milestone=? AND recipient_id=?
            """, Integer.class, deadline.workflow(), resource, due, milestone, user);
        if (count != null && count > 0) return false;
        jdbc.update("""
            INSERT INTO workflow_sla_alerts (workflow_type, resource_id, due_at, milestone, recipient_id, sent_at)
              VALUES (?, ?, ?, ?, ?, ?)
            """, deadline.workflow(), resource, due, milestone, user, Timestamp.from(now));
        return true;
    }

    private List<UUID> recipients(RoleCode role, UUID facility) {
        var eligible = switch (role) {
            case STAFF -> users.findEligibleStaff(UserStatus.ACTIVE, role, facility,
                List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE));
            case MANAGER -> users.findEligibleStaff(UserStatus.ACTIVE, role, facility, List.of(FacilityScopeLevel.MANAGE));
            default -> users.findDistinctByRoles_CodeAndStatus(role, UserStatus.ACTIVE);
        };
        return eligible.stream().map(User::getId).toList();
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
