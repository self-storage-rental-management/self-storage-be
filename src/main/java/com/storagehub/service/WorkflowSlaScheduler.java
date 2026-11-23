package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.EmailProperties;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.email.TransactionalEmailService;
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
                deadline.workflow().equals("CHECKIN_REJECTION") && canResolve(actor, deadline.facility())));
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
            List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled)), ReturnCase.class, item -> returnDeadline(item, schedule), lock, visitor);
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
            """, List.of(CheckInStatus.scheduled, CheckInStatus.rejected, CheckInStatus.handover_pending_receipt)),
            CheckIn.class, checkIn -> checkInDeadline(checkIn, now, schedule), lock, visitor);
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
        if (checkIn.getStatus() == CheckInStatus.scheduled && reservation.getStatus() == ReservationStatus.READY_FOR_CHECKIN) {
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

    private Deadline returnDeadline(ReturnCase returned, WorkflowBusinessCalendar.Schedule schedule) {
        if (!Set.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(returned.getStatus())
            || returned.getInspectedAt() != null) return null;
        Instant start = returned.getRequestedAt();
        if (returned.getScheduledDate() != null) {
            Instant appointmentDay = returned.getScheduledDate().atStartOfDay(BusinessTime.ZONE).toInstant();
            if (appointmentDay.isAfter(start)) start = appointmentDay;
        }
        return new Deadline("RETURN_INSPECTION", returned, returned.getFacility().getId(), start,
            schedule.after(start, 1), FOUR_HOURS, RoleCode.STAFF, RoleCode.MANAGER, null,
            NotificationType.RETURN, "kiểm tra yêu cầu trả kho");
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
