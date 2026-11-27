package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RentalSuspicionReportService {
    private static final String ACTION = "RENTAL_SUSPICION_REPORTED";
    private static final String ENTITY = "RentalSuspicionReport";
    private static final String PENDING = "PENDING_MANAGER";
    private static final String ACCEPT_ACTION = "RENTAL_SUSPICION_ACCEPTED";
    private static final String INVESTIGATING = "INVESTIGATING";
    private static final String TRANSFER_ACTION = "RENTAL_SUSPICION_TRANSFERRED";
    private static final String TRANSFERRED = "TRANSFERRED_TO_RECOVERY";
    private static final String CLOSE_ACTION = "RENTAL_SUSPICION_CLOSED";
    private static final String CLOSED = "CLOSED_NO_VIOLATION";
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(BusinessTime.ZONE);

    private final RentalQueryService queries;
    private final RentalRepository rentals;
    private final UserRepository users;
    private final ActivityLogRepository activity;
    private final AuditLogService audit;
    private final NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private BusinessRecordCodes displayCodes;
    @org.springframework.beans.factory.annotation.Autowired
    private ViolationRecoveryWorkflow recoveryWorkflow;

    public record Input(@NotNull UUID requestId, @NotNull @PastOrPresent Instant detectedAt,
        @NotBlank @Size(max = 1200) String description) {}

    public record Receipt(UUID id, String code, String status) {}

    private record Report(String code, String status, UUID rentalId, UUID reservationId, UUID facilityId,
        UUID unitId, UUID customerId, UUID reporterId, Instant submittedAt, Input observation) {}

    public record Detail(UUID id, String code, String status, UUID rentalId, Instant detectedAt,
        Instant submittedAt, String description, String reporterName, String facilityName,
        String acceptedByName, Instant acceptedAt) {}

    public record RentalContext(String displayCode, String reservationCode, String unitCode, String customerName) {}
    public record TransferInput(@NotBlank @Size(max = 2000) String conclusion,
        @NotBlank @Size(max = 2000) String grounds) {}
    public record Transfer(UUID recoveryId, String conclusion, String grounds, String decidedBy, Instant decidedAt) {}
    public record CloseInput(@NotBlank @Size(max = 2000) String reason) {}
    public record Closure(String reason, String decidedBy, Instant decidedAt) {}
    public record ManagerDetail(Detail report, RentalContext rental, Transfer transfer, Closure closure) {}

    @Transactional(readOnly = true)
    public ManagerDetail managerDetail(ActorPrincipal actor, UUID reportId) {
        var facilities = managedFacilities(actor);
        var log = activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(ENTITY, reportId, ACTION)
            .stream().filter(entry -> entry.getFacility() != null
                && facilities.contains(entry.getFacility().getId()))
            .findFirst().orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy báo cáo nghi vấn."));
        return managerDetail(log, acceptance(reportId));
    }

    @Transactional(readOnly = true)
    public List<Detail> staffList(ActorPrincipal actor, UUID rentalId) {
        var rental = queries.staffDetail(actor, rentalId);
        return withAcceptance(reportsForRental(rental.rental().facility().id(), rentalId));
    }

    @Transactional(readOnly = true)
    public List<Detail> managerRentalReports(ActorPrincipal actor, UUID rentalId) {
        var facilities = managedFacilities(actor);
        var rental = rentals.findById(rentalId)
            .filter(record -> facilities.contains(record.getFacility().getId()))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
        return withAcceptance(reportsForRental(rental.getFacility().getId(), rentalId));
    }

    private List<ActivityLog> reportsForRental(UUID facilityId, UUID rentalId) {
        return activity.findReportsForRental(ENTITY, ACTION, facilityId.toString(), rentalId.toString());
    }

    @Transactional(readOnly = true)
    public ManagerDetail staffReportDetail(ActorPrincipal actor, UUID rentalId, UUID reportId) {
        var rental = queries.staffDetail(actor, rentalId);
        var log = activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(ENTITY, reportId, ACTION)
            .stream().filter(entry -> entry.getFacility() != null
                && rental.rental().facility().id().equals(entry.getFacility().getId()))
            .findFirst().orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy báo cáo nghi vấn."));
        var transfer = transferLog(reportId);
        var closure = actionLog(reportId, CLOSE_ACTION);
        var report = detail(log, acceptance(reportId), terminalStatus(transfer != null, closure != null));
        if (!rentalId.equals(report.rentalId())) throw ApiExceptions.notFound("Không tìm thấy báo cáo nghi vấn.");
        var context = new RentalContext(rental.rental().displayCode(), rental.reservationCode(),
            rental.rental().storageUnit().code(), rental.rental().customer().fullName());
        return new ManagerDetail(report, context, transferDetail(transfer), closureDetail(closure));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ManagerDetail transferToRecovery(ActorPrincipal actor, UUID reportId, TransferInput input) {
        var log = lockManagerReport(actor, reportId);
        var accepted = acceptance(reportId);
        if (accepted == null) throw ApiExceptions.conflict("Cần tiếp nhận và xác minh báo cáo trước khi chuyển thu hồi.");
        if (transferLog(reportId) != null) return managerDetail(log, accepted);
        if (actionLog(reportId, CLOSE_ACTION) != null)
            throw ApiExceptions.conflict("Báo cáo đã đóng với kết luận không phát hiện hàng cấm.");
        var report = detail(log, accepted, null);
        var rental = rentals.findByIdForUpdate(report.rentalId())
            .filter(record -> log.getFacility().getId().equals(record.getFacility().getId()))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê tại cơ sở của báo cáo."));
        UUID recoveryId = recoveryWorkflow.openConfirmedProhibitedGoodsRecovery(actor, rental.getId());
        audit.recordMutation(users.getReferenceById(actor.userId()), TRANSFER_ACTION, ENTITY, reportId,
            log.getFacility().getId(), INVESTIGATING,
            java.util.Map.of("recoveryId", recoveryId, "conclusion", input.conclusion().strip(), "grounds", input.grounds().strip()));
        return managerDetail(log, accepted);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ManagerDetail closeWithoutViolation(ActorPrincipal actor, UUID reportId, CloseInput input) {
        var log = lockManagerReport(actor, reportId);
        var accepted = acceptance(reportId);
        if (accepted == null) throw ApiExceptions.conflict("Cần tiếp nhận và xác minh báo cáo trước khi đóng.");
        if (transferLog(reportId) != null) throw ApiExceptions.conflict("Báo cáo đã chuyển sang thu hồi.");
        if (actionLog(reportId, CLOSE_ACTION) == null) {
            audit.recordMutation(users.getReferenceById(actor.userId()), CLOSE_ACTION, ENTITY, reportId,
                log.getFacility().getId(), INVESTIGATING, new CloseInput(input.reason().strip()));
        }
        return managerDetail(log, accepted);
    }

    private ActivityLog transferLog(UUID reportId) {
        return actionLog(reportId, TRANSFER_ACTION);
    }

    private ActivityLog actionLog(UUID reportId, String action) {
        return activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(ENTITY, reportId, action)
            .stream().findFirst().orElse(null);
    }

    private Closure closureDetail(ActivityLog log) {
        if (log == null) return null;
        try {
            var data = objectMapper.readValue(log.getAfterStateJson(), CloseInput.class);
            return new Closure(data.reason(), log.getActor() == null ? null : log.getActor().getFullName(), log.getCreatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw ApiExceptions.conflict("Không thể đọc lý do đóng báo cáo.");
        }
    }

    private Transfer transferDetail(ActivityLog log) {
        if (log == null) return null;
        try {
            var data = objectMapper.readTree(log.getAfterStateJson());
            return new Transfer(UUID.fromString(data.path("recoveryId").asText()), data.path("conclusion").asText(),
                data.path("grounds").asText(), log.getActor() == null ? null : log.getActor().getFullName(), log.getCreatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            throw ApiExceptions.conflict("Không thể đọc kết luận chuyển thu hồi.");
        }
    }

    @Transactional
    public ManagerDetail accept(ActorPrincipal actor, UUID reportId) {
        var log = lockManagerReport(actor, reportId);
        var accepted = acceptance(reportId);
        if (accepted == null) {
            accepted = audit.recordMutation(users.getReferenceById(actor.userId()), ACCEPT_ACTION, ENTITY,
                reportId, log.getFacility().getId(), PENDING, INVESTIGATING);
        } else if (accepted.getActor() == null || !actor.userId().equals(accepted.getActor().getId())) {
            throw ApiExceptions.conflict("Báo cáo đã được một quản lý khác tiếp nhận.");
        }
        return managerDetail(log, accepted);
    }

    private ActivityLog lockManagerReport(ActorPrincipal actor, UUID reportId) {
        var facilities = managedFacilities(actor);
        if (facilities.isEmpty()) throw ApiExceptions.notFound("Không tìm thấy báo cáo nghi vấn.");
        return activity.findFirstByEntityTypeAndEntityIdAndActionAndFacility_IdInOrderByCreatedAtDesc(
            ENTITY, reportId, ACTION, facilities)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy báo cáo nghi vấn."));
    }

    private ActivityLog acceptance(UUID reportId) {
        return activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(ENTITY, reportId, ACCEPT_ACTION)
            .stream().findFirst().orElse(null);
    }

    private ManagerDetail managerDetail(ActivityLog log, ActivityLog accepted) {
        var transfer = transferLog(log.getEntityId());
        var closure = actionLog(log.getEntityId(), CLOSE_ACTION);
        var report = detail(log, accepted, terminalStatus(transfer != null, closure != null));
        var rental = rentals.findById(report.rentalId())
            .filter(record -> log.getFacility().getId().equals(record.getFacility().getId()))
            .map(record -> new RentalContext(displayCodes.find("TH:" + record.getId()),
                record.getReservation().getReservationCode(), record.getStorageUnit().getCode(),
                record.getCustomer().getFullName()))
            .orElse(null);
        return new ManagerDetail(report, rental, transferDetail(transfer), closureDetail(closure));
    }

    @Transactional(readOnly = true)
    public com.storagehub.common.api.PageResponse<Detail> managerList(ActorPrincipal actor, int page, int size, String correlationId) {
        var facilities = managedFacilities(actor);
        if (page < 0 || size < 1 || size > 100) throw ApiExceptions.validation("Phân trang không hợp lệ.", null);
        var pagination = org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
        var logs = facilities.isEmpty() ? org.springframework.data.domain.Page.<com.storagehub.domain.model.ActivityLog>empty(pagination)
            : activity.findByEntityTypeAndActionAndFacility_IdIn(ENTITY, ACTION, facilities, pagination);
        return com.storagehub.common.api.PageResponse.from(
            new org.springframework.data.domain.PageImpl<>(withAcceptance(logs.getContent()), pagination,
                logs.getTotalElements()), correlationId);
    }

    private List<Detail> withAcceptance(List<ActivityLog> logs) {
        if (logs.isEmpty()) return List.of();
        var accepted = new java.util.HashMap<UUID, ActivityLog>();
        var terminal = new java.util.HashMap<UUID, String>();
        activity.findByEntityTypeAndActionInAndEntityIdIn(ENTITY, List.of(ACCEPT_ACTION, TRANSFER_ACTION, CLOSE_ACTION),
            logs.stream().map(ActivityLog::getEntityId).toList()).forEach(entry -> {
                switch (entry.getAction()) {
                    case ACCEPT_ACTION -> accepted.put(entry.getEntityId(), entry);
                    case TRANSFER_ACTION -> terminal.put(entry.getEntityId(), TRANSFERRED);
                    case CLOSE_ACTION -> terminal.put(entry.getEntityId(), CLOSED);
                    default -> throw new IllegalStateException("Unexpected suspicion report action");
                }
            });
        return logs.stream().map(log -> detail(log, accepted.get(log.getEntityId()), terminal.get(log.getEntityId()))).toList();
    }

    private String terminalStatus(boolean transferred, boolean closed) {
        if (transferred) return TRANSFERRED;
        return closed ? CLOSED : null;
    }

    private java.util.Set<UUID> managedFacilities(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.MANAGER)
            || !actor.hasPermission(com.storagehub.domain.model.SystemPermission.VIEW_RENTALS))
            throw ApiExceptions.forbidden("Cần quyền quản lý hồ sơ thuê.");
        return actor.facilityScopes().entrySet().stream()
            .filter(scope -> scope.getValue() == FacilityScopeLevel.MANAGE)
            .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    private Detail detail(ActivityLog log, ActivityLog accepted, String terminalStatus) {
        try {
            var report = objectMapper.readTree(log.getAfterStateJson());
            var observation = report.path("observation");
            return new Detail(log.getEntityId(), report.path("code").asText(), terminalStatus != null ? terminalStatus : accepted == null ? PENDING : INVESTIGATING,
                UUID.fromString(report.path("rentalId").asText()),
                objectMapper.treeToValue(observation.path("detectedAt"), Instant.class),
                objectMapper.treeToValue(report.path("submittedAt"), Instant.class),
                observation.path("description").asText(),
                log.getActor() == null ? null : log.getActor().getFullName(), log.getFacility().getName(),
                accepted == null || accepted.getActor() == null ? null : accepted.getActor().getFullName(),
                accepted == null ? null : accepted.getCreatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            throw ApiExceptions.conflict("Không thể đọc dữ liệu báo cáo nghi vấn.");
        }
    }

    // Staff scope checks may read before the rental lock. Read committed makes the
    // subsequent report lookup see reports committed by the previous lock holder.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt submit(ActorPrincipal actor, UUID rentalId, Input input) {
        var detail = queries.staffDetail(actor, rentalId);
        UUID facilityId = detail.rental().facility().id();
        if (!actor.facilityScopes().get(facilityId).includes(FacilityScopeLevel.OPERATE))
            throw ApiExceptions.forbidden("Cần quyền vận hành tại cơ sở để gửi báo cáo.");
        var rental = rentals.findByIdForUpdate(rentalId)
            .filter(record -> facilityId.equals(record.getFacility().getId()))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
        return submit(actor, rental, facilityId, detail.reservationId(), detail.reservationCode(),
            detail.rental().storageUnit(), detail.rental().customer().id(), input, RoleCode.STAFF);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt submitAsManager(ActorPrincipal actor, UUID rentalId, Input input) {
        var facilities = managedFacilities(actor);
        var rental = rentals.findByIdForUpdate(rentalId)
            .filter(record -> facilities.contains(record.getFacility().getId()))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
        var unit = rental.getStorageUnit();
        return submit(actor, rental, rental.getFacility().getId(), rental.getReservation().getId(),
            rental.getReservation().getReservationCode(),
            new com.storagehub.api.rental.RentalSummaryResponse.Unit(unit.getId(), unit.getCode()),
            rental.getCustomer().getId(), input, RoleCode.MANAGER);
    }

    private Receipt submit(ActorPrincipal actor, com.storagehub.domain.model.Rental rental,
        UUID facilityId, UUID reservationId, String reservationCode,
        com.storagehub.api.rental.RentalSummaryResponse.Unit unit, UUID customerId, Input input, RoleCode role) {
        UUID rentalId = rental.getId();
        UUID reportId = UUID.nameUUIDFromBytes((actor.userId() + ":" + rentalId + ":" + input.requestId())
            .getBytes(StandardCharsets.UTF_8));
        String code = "NV-" + reportId.toString().substring(0, 13).toUpperCase(java.util.Locale.ROOT);
        if (!activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(ENTITY, reportId, ACTION).isEmpty())
            return new Receipt(reportId, code, PENDING);
        var existing = reportsForRental(facilityId, rentalId);
        if (!existing.isEmpty()) {
            var reportIds = existing.stream().map(ActivityLog::getEntityId).toList();
            long resolved = activity.findByEntityTypeAndActionInAndEntityIdIn(
                ENTITY, List.of(TRANSFER_ACTION, CLOSE_ACTION), reportIds).stream()
                .map(ActivityLog::getEntityId).distinct().count();
            if (reportIds.size() > resolved)
                throw ApiExceptions.conflict("Hồ sơ thuê đã có báo cáo nghi vấn chưa được xử lý xong.");
        }
        if (rental.getStatus() != RentalStatus.active)
            throw ApiExceptions.conflict("Chỉ gửi báo cáo từ hồ sơ đang thuê.");

        var reporter = users.getReferenceById(actor.userId());
        audit.recordMutation(reporter, ACTION, ENTITY, reportId, facilityId, null,
            new Report(code, PENDING, rentalId, reservationId, facilityId, unit.id(),
                customerId, actor.userId(), Instant.now(), input));

        String title = "Báo cáo nghi vấn hàng cấm";
        String content = code + " · " + unit.code() + " · " + reservationCode
            + "\nPhát hiện lúc " + LOCAL_TIME.format(input.detectedAt())
            + "\nNgười báo: " + reporter.getFullName() + "\n" + input.description().strip();
        users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facilityId, List.of(FacilityScopeLevel.MANAGE))
            .stream().map(user -> user.getId()).distinct().filter(id -> !id.equals(actor.userId())).forEach(managerId ->
                notifications.createNotification(managerId, NotificationType.SYSTEM, title, content, reportId, role));
        notifications.createNotification(actor.userId(), NotificationType.SYSTEM,
            "Đã gửi báo cáo nghi vấn hàng cấm",
            code + " · " + unit.code() + " · Chờ quản lý tiếp nhận.",
            reportId, role);
        return new Receipt(reportId, code, PENDING);
    }
}
