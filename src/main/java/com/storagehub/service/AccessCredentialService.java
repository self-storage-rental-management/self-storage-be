package com.storagehub.service;

import com.storagehub.api.credential.AccessCredentialResponse;
import com.storagehub.api.credential.CredentialAccessResponse;
import com.storagehub.api.credential.CredentialAlertResponse;
import com.storagehub.api.credential.CredentialAccessEventResponse;
import com.storagehub.api.credential.CredentialAccessEventType;
import com.storagehub.api.credential.IssueAccessCredentialRequest;
import com.storagehub.api.credential.RecordCredentialAccessEventRequest;
import com.storagehub.api.credential.RecordCredentialAccessRequest;
import com.storagehub.api.credential.RevokeAccessCredentialRequest;
import com.storagehub.api.credential.ReissueAccessCredentialRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.AccessCredential;
import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.AccessCredentialType;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.AccessCredentialRepository;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccessCredentialService {

    private static final Pattern ACCESS_WINDOW = Pattern.compile("(\\d{1,2}:\\d{2}).*?(\\d{1,2}:\\d{2})");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern ACCESS_HOURS_PATTERN = Pattern.compile(
        "(\\d{1,2})(?::|h)?(\\d{2})?\\s*[-–]\\s*(\\d{1,2})(?::|h)?(\\d{2})?",
        Pattern.CASE_INSENSITIVE
    );

    private final AccessCredentialRepository credentialRepository;
    private final CheckInRepository checkInRepository;
    private final ReservationRepository reservationRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final RentalRepository rentalRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Transactional
    public AccessCredentialResponse issue(
        ActorPrincipal actor,
        UUID checkInId,
        IssueAccessCredentialRequest request
    ) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.payment_collected
            || !isCheckInReadyStatus(reservation.getStatus())) {
            throw ApiExceptions.conflict("Credentials can only be activated after payment and before setup");
        }
        if (request.type() != AccessCredentialType.LOCK) {
            throw ApiExceptions.validation("Only a lock credential can be handed over during check-in", null);
        }
        UnitAssignment assignment = assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        ).orElseThrow(() -> ApiExceptions.conflict("Reservation has no active unit assignment"));
        StorageUnit unit = assignment.getStorageUnit();
        if (unit.getStatus() != StorageUnitStatus.reserved) {
            throw ApiExceptions.conflict("Storage unit is not reserved for handover");
        }
        var existingRental = rentalRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (existingRental != null && existingRental.getSetupStartedAt() == null) {
            throw ApiExceptions.conflict("Reservation or storage unit already has an active rental");
        }
        var existing = credentialRepository.findByReservation_IdAndStatus(
            reservation.getId(), AccessCredentialStatus.ACTIVE
        ).stream().filter(item -> item.getType() == request.type()).findFirst();
        if (existing.isPresent()) {
            if (existing.get().getReference().equals(request.reference().trim())) return toResponse(existing.get());
            throw ApiExceptions.conflict("An active credential of this type already exists for the reservation");
        }

        User staff = requireActor(actor);
        AccessCredential credential = new AccessCredential();
        credential.setReservation(reservation);
        credential.setFacility(reservation.getFacility());
        credential.setStorageUnit(unit);
        credential.setCustomer(reservation.getCustomer());
        credential.setType(request.type());
        credential.setStatus(AccessCredentialStatus.ACTIVE);
        credential.setReference(request.reference().trim());
        credential.setSecretHash(null);
        credential.setIssuedBy(staff);
        credential.setIssuedAt(Instant.now());
        String accessHours = clean(reservation.getFacility().getAccessHours());
        String accessPolicy = accessHours == null
            ? "Truy cập theo giờ mở cửa cơ sở; thời điểm kích hoạt và thu hồi được ghi nhận trong nhật ký vận hành"
            : "Truy cập trong giờ mở cửa cơ sở (" + accessHours
                + "); thời điểm kích hoạt và thu hồi được ghi nhận trong nhật ký vận hành";
        String requestNote = clean(request.note());
        credential.setNote(requestNote == null ? accessPolicy : requestNote + ". " + accessPolicy);
        AccessCredential saved = credentialRepository.saveAndFlush(credential);
        auditLogService.recordMutation(
            staff, "ACCESS_CREDENTIAL_ISSUED", "AccessCredential", saved.getId(),
            reservation.getFacility().getId(), null, toResponse(saved)
        );
        return toResponse(saved);
    }

    @Transactional
    public AccessCredentialResponse revoke(
        ActorPrincipal actor,
        UUID credentialId,
        RevokeAccessCredentialRequest request
    ) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        AccessCredential credential = credentialRepository.findByIdForUpdate(credentialId)
            .orElseThrow(() -> ApiExceptions.notFound("Access credential was not found"));
        facilityScopeService.assertCanOperate(actor, credential.getFacility().getId());
        if (credential.getStatus() == AccessCredentialStatus.REVOKED) {
            return toResponse(credential);
        }
        User staff = requireActor(actor);
        revokeEntity(credential, staff, request.reason());
        AccessCredential saved = credentialRepository.saveAndFlush(credential);
        auditLogService.recordMutation(
            staff, "ACCESS_CREDENTIAL_REVOKED", "AccessCredential", saved.getId(),
            saved.getFacility().getId(), Map.of("status", AccessCredentialStatus.ACTIVE), toResponse(saved)
        );
        return toResponse(saved);
    }

    @Transactional
    public CredentialAccessEventResponse recordAccessEvent(
        ActorPrincipal actor,
        UUID credentialId,
        RecordCredentialAccessEventRequest request
    ) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        AccessCredential credential = credentialRepository.findByIdForUpdate(credentialId)
            .orElseThrow(() -> ApiExceptions.notFound("Access credential was not found"));
        facilityScopeService.assertCanOperate(actor, credential.getFacility().getId());

        Instant occurredAt = Instant.now();
        Rental rental = credential.getRental();
        boolean active = credential.getStatus() == AccessCredentialStatus.ACTIVE;
        boolean rentalOpen = rental != null
            && (rental.getStatus() == RentalStatus.setup || rental.getStatus() == RentalStatus.active);
        boolean withinHours = withinAccessHours(credential.getFacility().getAccessHours(), occurredAt);
        boolean allowed = active && rentalOpen && withinHours;
        String reason = !active ? "Credential đã bị thu hồi"
            : !rentalOpen ? "Rental không còn ở trạng thái setup hoặc active"
            : !withinHours ? "Ngoài giờ mở cửa cơ sở"
            : "Truy cập hợp lệ trong giờ mở cửa";

        User staff = requireActor(actor);
        auditLogService.recordMutation(
            staff,
            allowed ? "ACCESS_EVENT_ALLOWED" : "ACCESS_EVENT_DENIED",
            "AccessCredential",
            credential.getId(),
            credential.getFacility().getId(),
            null,
            Map.of(
                "eventType", request.eventType().name(),
                "occurredAt", occurredAt.toString(),
                "allowed", allowed,
                "reason", reason,
                "note", clean(request.note()) == null ? "" : clean(request.note()),
                "rentalId", rental == null ? "" : rental.getId().toString()
            )
        );
        return new CredentialAccessEventResponse(
            credential.getId(),
            rental == null ? null : rental.getId(),
            credential.getFacility().getId(),
            request.eventType(),
            occurredAt,
            allowed,
            reason
        );
    }

    @Transactional
    public AccessCredentialResponse reissue(
        ActorPrincipal actor,
        UUID rentalId,
        ReissueAccessCredentialRequest request
    ) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        if (!Boolean.TRUE.equals(request.identityVerified())) {
            throw ApiExceptions.validation("Customer identity verification is required", null);
        }
        String verificationMethod = clean(request.verificationMethod());
        String reason = clean(request.reason());
        String reference = clean(request.reference());
        if (verificationMethod == null || reason == null || reference == null
            || !List.of("ID_DOCUMENT", "CUSTOMER_ACCOUNT", "AUTHORIZED_REPRESENTATIVE", "OTHER").contains(verificationMethod)) {
            throw ApiExceptions.validation("Verification method, reason and replacement reference are required", null);
        }

        Rental rental = rentalRepository.findByIdForUpdate(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));
        facilityScopeService.assertCanOperate(actor, rental.getFacility().getId());
        if (rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Access credentials can only be reissued for an active rental");
        }

        AccessCredential current = credentialRepository.findByIdForUpdate(request.credentialId())
            .orElseThrow(() -> ApiExceptions.notFound("Access credential was not found"));
        if (current.getRental() == null || !rental.getId().equals(current.getRental().getId())
            || !rental.getCustomer().getId().equals(current.getCustomer().getId())
            || !rental.getFacility().getId().equals(current.getFacility().getId())) {
            throw ApiExceptions.notFound("Access credential was not found for this rental");
        }
        if (current.getStatus() != AccessCredentialStatus.ACTIVE) {
            throw ApiExceptions.conflict("Access credential is no longer active");
        }

        User staff = requireActor(actor);
        revokeEntity(current, staff, reason);
        AccessCredential revoked = credentialRepository.saveAndFlush(current);

        AccessCredential replacement = new AccessCredential();
        replacement.setReservation(rental.getReservation());
        replacement.setRental(rental);
        replacement.setFacility(rental.getFacility());
        replacement.setStorageUnit(rental.getStorageUnit());
        replacement.setCustomer(rental.getCustomer());
        replacement.setType(current.getType());
        replacement.setStatus(AccessCredentialStatus.ACTIVE);
        replacement.setReference(reference);
        replacement.setSecretHash(hashSecret(request.secret()));
        replacement.setIssuedBy(staff);
        replacement.setIssuedAt(Instant.now());
        replacement.setNote(clean(request.note()));
        AccessCredential saved = credentialRepository.saveAndFlush(replacement);

        Map<String, Object> auditContext = Map.of(
            "rentalId", rental.getId().toString(),
            "replacedCredentialId", revoked.getId().toString(),
            "identityVerified", true,
            "verificationMethod", verificationMethod,
            "reason", reason
        );
        auditLogService.recordMutation(
            staff, "ACCESS_CREDENTIAL_REISSUE_REVOKED", "AccessCredential", revoked.getId(),
            rental.getFacility().getId(), Map.of("status", AccessCredentialStatus.ACTIVE),
            Map.of("status", AccessCredentialStatus.REVOKED, "reissue", auditContext)
        );
        auditLogService.recordMutation(
            staff, "ACCESS_CREDENTIAL_REISSUED", "AccessCredential", saved.getId(),
            rental.getFacility().getId(), auditContext, toResponse(saved)
        );
        notificationService.createNotification(
            rental.getCustomer().getId(), NotificationType.CHECKIN,
            "Quyền truy cập gian kho đã được thay mới",
            "Nhân viên đã thay quyền " + saved.getType() + " cho gian " + rental.getStorageUnit().getCode()
                + ". Mã tham chiếu mới: " + saved.getReference() + ". Nếu bạn không yêu cầu thao tác này, hãy liên hệ cơ sở.",
            rental.getId()
        );
        return toResponse(saved);
    }

    /** Records a credential access attempt and applies the facility opening-hours policy. */
    @Transactional
    public CredentialAccessResponse recordAccess(
        ActorPrincipal actor,
        UUID credentialId,
        RecordCredentialAccessRequest request
    ) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        AccessCredential credential = credentialRepository.findByIdForUpdate(credentialId)
            .orElseThrow(() -> ApiExceptions.notFound("Access credential was not found"));
        facilityScopeService.assertCanOperate(actor, credential.getFacility().getId());

        Instant accessedAt = request != null && request.accessedAt() != null
            ? request.accessedAt() : Instant.now();
        if (accessedAt.isAfter(Instant.now().plusSeconds(30))) {
            throw ApiExceptions.validation("Access time cannot be in the future", null);
        }
        String accessHours = clean(credential.getFacility().getAccessHours());
        boolean withinOpeningHours = isWithinOpeningHours(accessHours, accessedAt);
        Rental rental = credential.getRental();
        boolean duringSetup = rental != null && rental.getStatus() == RentalStatus.setup;
        boolean allowed = credential.getStatus() == AccessCredentialStatus.ACTIVE && withinOpeningHours;
        String reason;
        if (credential.getStatus() != AccessCredentialStatus.ACTIVE) {
            reason = "Credential đã bị thu hồi";
        } else if (accessHours == null) {
            reason = "Cơ sở chưa cấu hình giờ mở cửa";
        } else if (!withinOpeningHours) {
            reason = "Ngoài giờ mở cửa cơ sở (" + accessHours + ")";
        } else {
            reason = "Được phép truy cập trong giờ mở cửa cơ sở";
        }

        Map<String, Object> accessLog = new LinkedHashMap<>();
        accessLog.put("credentialId", credential.getId());
        accessLog.put("rentalId", rental == null ? null : rental.getId());
        accessLog.put("accessedAt", accessedAt);
        accessLog.put("duringSetup", duringSetup);
        accessLog.put("withinOpeningHours", withinOpeningHours);
        accessLog.put("allowed", allowed);
        accessLog.put("source", request == null ? null : clean(request.source()));
        accessLog.put("note", request == null ? null : clean(request.note()));
        accessLog.put("reason", reason);
        User staff = requireActor(actor);
        auditLogService.recordMutation(
            staff,
            allowed ? "ACCESS_CREDENTIAL_ACCESS_ALLOWED" : "ACCESS_CREDENTIAL_ACCESS_DENIED",
            "AccessCredential",
            credential.getId(),
            credential.getFacility().getId(),
            null,
            accessLog
        );
        return new CredentialAccessResponse(
            credential.getId(), rental == null ? null : rental.getId(), accessedAt,
            allowed, duringSetup, reason, accessHours
        );
    }

    /** Called by Staff return inspection to revoke every credential for the rental. */
    @Transactional
    public int revokeForRental(ActorPrincipal actor, Rental rental, String reason) {
        requireStaff(actor);
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        facilityScopeService.assertCanOperate(actor, rental.getFacility().getId());
        User staff = requireActor(actor);
        List<AccessCredential> active = credentialRepository.findByRental_IdAndStatus(
            rental.getId(), AccessCredentialStatus.ACTIVE
        );
        int count = 0;
        for (AccessCredential credential : active) {
            revokeEntity(credential, staff, reason);
            credentialRepository.save(credential);
            auditLogService.recordMutation(
                staff, "ACCESS_CREDENTIAL_REVOKED", "AccessCredential", credential.getId(),
                rental.getFacility().getId(), Map.of("status", AccessCredentialStatus.ACTIVE), toResponse(credential)
            );
            count++;
        }
        return count;
    }

    /** Links credentials issued at handover to the Rental created after customer receipt. */
    @Transactional
    public void attachToRental(Reservation reservation, Rental rental) {
        for (AccessCredential credential : credentialRepository.findByReservation_IdAndStatus(
            reservation.getId(), AccessCredentialStatus.ACTIVE
        )) {
            credential.setRental(rental);
            credentialRepository.save(credential);
        }
    }

    @Transactional(readOnly = true)
    public boolean hasActiveForReservation(UUID reservationId) {
        return credentialRepository.existsByReservation_IdAndStatus(
            reservationId, AccessCredentialStatus.ACTIVE
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessCredentialResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        String q,
        AccessCredentialStatus status,
        int page,
        int pageSize,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        if (facilityId != null) facilityScopeService.assertCanRead(actor, facilityId);
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        Collection<UUID> facilityIds = scoped && !actor.facilityScopes().isEmpty()
            ? actor.facilityScopes().keySet()
            : List.of(new UUID(0, 0));
        Collection<AccessCredentialStatus> statuses = status == null
            ? List.of(AccessCredentialStatus.ACTIVE, AccessCredentialStatus.REVOKED)
            : List.of(status);
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw ApiExceptions.validation("page must be >= 0 and pageSize must be between 1 and 100", null);
        }
        var result = credentialRepository.search(
            facilityId, statuses, scoped, facilityIds, clean(q),
            PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "issuedAt"))
        ).map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public List<CredentialAlertResponse> alerts(ActorPrincipal actor, UUID facilityId) {
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        if (facilityId != null) facilityScopeService.assertCanRead(actor, facilityId);
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        Collection<UUID> allowed = scoped ? actor.facilityScopes().keySet() : List.of();
        List<Reservation> reservations = scoped
            ? (allowed.isEmpty()
                ? List.of()
                : reservationRepository.findByFacility_IdInAndStatusInAndAssignedUnitIsNotNull(
                    allowed, List.of(ReservationStatus.READY_FOR_CHECKIN)))
            : reservationRepository.findByStatusInAndAssignedUnitIsNotNull(
                List.of(ReservationStatus.READY_FOR_CHECKIN));
        List<CredentialAlertResponse> alerts = new ArrayList<>();
        for (Reservation reservation : reservations) {
            if (facilityId != null && !facilityId.equals(reservation.getFacility().getId())) continue;
            if (assignmentRepository.findByReservation_IdAndStatus(
                reservation.getId(), UnitAssignmentStatus.ACTIVE
            ).isEmpty()) continue;
            CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
            if (checkIn == null || checkIn.getStatus() != CheckInStatus.scheduled
                || credentialRepository.existsByReservation_IdAndStatus(reservation.getId(), AccessCredentialStatus.ACTIVE)) {
                continue;
            }
            alerts.add(new CredentialAlertResponse(
                checkIn.getId(), "ISSUE", reservation.getId(), reservation.getReservationCode(), null,
                reservation.getFacility().getId(), reservation.getFacility().getName(),
                reservation.getAssignedUnit().getId(), reservation.getAssignedUnit().getCode(),
                reservation.getCustomer().getId(), reservation.getCustomer().getFullName(), null, null,
                checkIn.getScheduledAt(), "Hồ sơ đã sẵn sàng nhận kho nhưng chưa cấp mã ổ khóa"
            ));
        }

        List<ReturnCase> returns = scoped
            ? (allowed.isEmpty()
                ? List.of()
                : returnCaseRepository.findByFacility_IdInAndStatusIn(allowed, List.of(
                    ReturnCaseStatus.requested, ReturnCaseStatus.scheduled, ReturnCaseStatus.inspected,
                    ReturnCaseStatus.awaiting_customer_confirmation, ReturnCaseStatus.disputed,
                    ReturnCaseStatus.payment_due, ReturnCaseStatus.refund_pending)))
            : returnCaseRepository.findByStatusIn(List.of(
                ReturnCaseStatus.requested, ReturnCaseStatus.scheduled, ReturnCaseStatus.inspected,
                ReturnCaseStatus.awaiting_customer_confirmation, ReturnCaseStatus.disputed,
                ReturnCaseStatus.payment_due, ReturnCaseStatus.refund_pending));
        for (ReturnCase returnCase : returns) {
            if (facilityId != null && !facilityId.equals(returnCase.getFacility().getId())) continue;
            Rental rental = returnCase.getRental();
            for (AccessCredential credential : credentialRepository.findByRental_IdAndStatus(
                rental.getId(), AccessCredentialStatus.ACTIVE)) {
                alerts.add(new CredentialAlertResponse(
                    credential.getId(), "REVOKE", returnCase.getId(), rental.getReservation().getReservationCode(),
                    rental.getId(), returnCase.getFacility().getId(), returnCase.getFacility().getName(),
                    rental.getStorageUnit().getId(), rental.getStorageUnit().getCode(),
                    rental.getCustomer().getId(), rental.getCustomer().getFullName(), credential.getType(),
                    credential.getReference(), returnCase.getScheduledDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    "Có credential đang hoạt động cần thu hồi khi nghiệm thu trả kho"
                ));
            }
        }
        return alerts;
    }

    private void revokeEntity(AccessCredential credential, User staff, String reason) {
        credential.setStatus(AccessCredentialStatus.REVOKED);
        credential.setRevokedBy(staff);
        credential.setRevokedAt(Instant.now());
        credential.setNote(clean(reason));
    }

    private User requireActor(ActorPrincipal actor) {
        return userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
    }

    private void requireStaff(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.STAFF) || actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only Staff may issue or revoke access credentials");
        }
    }

    private boolean isCheckInReadyStatus(ReservationStatus status) {
        return status == ReservationStatus.UNIT_RESERVED
            || status == ReservationStatus.READY_FOR_CHECKIN;
    }

    private String hashSecret(String secret) {
        if (secret == null || secret.isBlank()) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean withinAccessHours(String raw, Instant occurredAt) {
        if (raw == null || raw.isBlank()) return true;
        Matcher matcher = ACCESS_WINDOW.matcher(raw);
        if (!matcher.find()) return true;
        try {
            LocalTime start = LocalTime.parse(matcher.group(1));
            LocalTime end = LocalTime.parse(matcher.group(2));
            LocalTime now = occurredAt.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalTime();
            if (start.equals(end)) return true;
            return start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean isWithinOpeningHours(String accessHours, Instant instant) {
        if (accessHours == null) return false;
        Matcher matcher = ACCESS_HOURS_PATTERN.matcher(accessHours);
        if (!matcher.find()) return false;
        try {
            LocalTime opens = parseTime(matcher.group(1), matcher.group(2));
            LocalTime closes = parseTime(matcher.group(3), matcher.group(4));
            LocalTime current = instant.atZone(VIETNAM_ZONE).toLocalTime();
            if (opens.equals(closes)) return true;
            if (opens.isBefore(closes)) {
                return !current.isBefore(opens) && current.isBefore(closes);
            }
            return !current.isBefore(opens) || current.isBefore(closes);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private LocalTime parseTime(String hourValue, String minuteValue) {
        int hour = Integer.parseInt(hourValue);
        int minute = minuteValue == null || minuteValue.isBlank() ? 0 : Integer.parseInt(minuteValue);
        return LocalTime.of(hour, minute);
    }
    private AccessCredentialResponse toResponse(AccessCredential credential) {
        return new AccessCredentialResponse(
            credential.getId(), credential.getReservation().getId(), credential.getReservation().getReservationCode(),
            credential.getRental() == null ? null : credential.getRental().getId(),
            credential.getFacility().getId(), credential.getFacility().getName(),
            credential.getStorageUnit().getId(), credential.getStorageUnit().getCode(),
            credential.getCustomer().getId(), credential.getCustomer().getFullName(), credential.getType(),
            credential.getStatus(), credential.getReference(), credential.getIssuedBy().getId(),
            credential.getIssuedBy().getFullName(), credential.getIssuedAt(),
            credential.getRevokedBy() == null ? null : credential.getRevokedBy().getId(),
            credential.getRevokedBy() == null ? null : credential.getRevokedBy().getFullName(),
            credential.getRevokedAt(), credential.getNote()
        );
    }
}
