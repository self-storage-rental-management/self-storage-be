package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntakeWorkflowService {
    private final RentalRepository rentals;
    private final CheckInRepository checkIns;
    private final FileAssetRepository files;
    private final FacilityScopeService scope;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final ReturnCaseRepository returns;

    @Transactional
    public void readyForReturn(ActorPrincipal actor, UUID id) {
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận bàn giao.");
        var rc = returns.findByIdForUpdate(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ trả kho."));
        scope.assertCanOperate(actor, rc.getFacility().getId());
        if (!List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(rc.getStatus()))
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước chờ nghiệm thu.");
        if (rc.getReadyForInspectionAt() == null) rc.setReadyForInspectionAt(Instant.now());
        returns.saveAndFlush(rc);
    }

    public record Command(String action, Instant at, String reason, String documentNumber, UUID photoId, Integer extensionDays) {
        public Command(String action, Instant at, String reason, String documentNumber, UUID photoId) {
            this(action, at, reason, documentNumber, photoId, null);
        }
    }

    @Transactional(readOnly = true)
    public Map<String,Object> read(ActorPrincipal actor, UUID reservationId) {
        Rental r = rentals.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Chưa có hồ sơ thuê."));
        access(actor, r);
        return view(r);
    }

    @Transactional
    public Map<String,Object> change(ActorPrincipal actor, UUID reservationId, Command command) {
        Rental r = rentals.findByReservationIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Chưa có hồ sơ thuê."));
        access(actor, r);
        if (command == null || command.action() == null) throw ApiExceptions.validation("Vui lòng chọn thao tác.", null);
        if (!List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())
            && !("FACILITY_EXTENSION".equals(command.action()) && actor.hasRole(RoleCode.MANAGER)
                && r.getStatus() == RentalStatus.return_requested))
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước setup hoặc kiểm kê.");
        Instant now = Instant.now();
        Instant before = null;
        switch (command.action()) {
            case "EXTEND_SETUP" -> {
                customer(actor, r);
                // A retried command must not consume another extension.
                if (Objects.equals(command.at(), r.getSetupCompletionDeadlineAt())) return view(r);
                if (!SetupCompletionPolicy.canExtend(r, now, command.at()))
                    throw ApiExceptions.conflict("Không thể dời hạn này. Vui lòng kiểm tra thời hạn và số lần còn lại.");
                before = r.getSetupCompletionDeadlineAt();
                r.setSetupCompletionDeadlineAt(command.at());
                r.setSetupExtensionCount(r.getSetupExtensionCount() + 1);
            }
            case "APPOINTMENT" -> {
                customer(actor, r);
                if (r.getMoveInCompletedAt() == null || r.getInventoryArrivedAt() != null
                    || r.getInventoryRemovalPublishedAt() != null || r.getInventoryEscalatedAt() != null
                    || r.getInventoryFinalDeadlineAt() == null || command.at() == null
                    || command.at().isBefore(now) || !command.at().isBefore(r.getInventoryFinalDeadlineAt()))
                    throw ApiExceptions.conflict("Vui lòng chọn lịch kiểm kê còn trong thời hạn.");
                CheckIn checkIn = checkIns.findByReservation_Id(reservationId).orElse(null);
                before = r.getInventoryAppointmentAt() != null
                    ? r.getInventoryAppointmentAt()
                    : checkIn == null ? null : checkIn.getInventoryAppointmentAt();
                if (Objects.equals(before, command.at())) return view(r);
                r.setInventoryAppointmentAt(command.at());
                if (checkIn != null) {
                    checkIn.setInventoryAppointmentAt(command.at());
                    checkIns.saveAndFlush(checkIn);
                }
            }
            case "ARRIVAL" -> {
                staff(actor, r);
                if (r.getInventoryArrivedAt() != null) return view(r);
                if (r.getMoveInCompletedAt() == null || r.getInventoryFinalDeadlineAt() == null
                    || !now.isBefore(r.getInventoryFinalDeadlineAt()) || r.getInventoryEscalatedAt() != null)
                    throw ApiExceptions.conflict("Hồ sơ không còn trong hạn tiếp nhận kiểm kê.");
                r.setInventoryArrivedAt(now);
            }
            case "VERIFY" -> {
                staff(actor, r);
                if (r.getInventoryArrivedAt() == null) throw ApiExceptions.conflict("Vui lòng ghi nhận khách đã đến.");
                String declared = r.getReservation().getIdentityDocumentNumber();
                if (declared == null || command.documentNumber() == null
                    || !declared.replaceAll("\\s", "").equalsIgnoreCase(command.documentNumber().replaceAll("\\s", "")))
                    throw ApiExceptions.conflict("Giấy tờ không khớp người đăng ký thuê.");
                CheckIn c = checkIns.findByReservation_Id(reservationId).orElseThrow();
                var f = command.photoId() == null ? null : files.findById(command.photoId()).orElse(null);
                if (f == null || f.getStatus() != FileAssetStatus.ACTIVE || f.getContentType() == null
                    || !f.getContentType().startsWith("image/") || !"CHECK_IN".equals(f.getEntityType())
                    || !c.getId().equals(f.getEntityId()) || f.getCreatedAt().isBefore(r.getInventoryArrivedAt())
                    || !actor.userId().equals(f.getUploadedBy().getId()))
                    throw ApiExceptions.conflict("Vui lòng tải ảnh tại buổi kiểm kê vào đúng hồ sơ.");
                r.setInventoryIdentityPhotoId(f.getId());
                r.setInventoryIdentityVerifiedAt(now);
                r.setInventoryIdentityVerifiedBy(actor.userId());
            }
            case "FACILITY_EXTENSION" -> {
                if (!actor.hasRole(RoleCode.MANAGER)) throw ApiExceptions.forbidden("Chỉ Manager được điều chỉnh do cơ sở.");
                scope.assertCanManage(actor, r.getFacility().getId());
                if (command.reason() == null || command.reason().isBlank() || command.at() == null || !command.at().isAfter(now))
                    throw ApiExceptions.validation("Vui lòng nhập lý do và thời hạn mới.", null);
                if (r.getInventoryEscalatedAt() != null) throw ApiExceptions.conflict("Hồ sơ đã chuyển thu hồi, cần xử lý nhiệm vụ hiện có.");
                if (r.getMoveInCompletedAt() == null) {
                    if (r.getSetupExtensionDays() == null) {
                        if (command.extensionDays() == null || command.extensionDays() < 1)
                            throw ApiExceptions.validation("Vui lòng cấu hình số ngày tối đa mỗi lần dời hạn cho cỡ kho này.", null);
                        r.setSetupExtensionDays(command.extensionDays()); r.setSetupExtensionCount(0);
                    }
                    before = r.getSetupCompletionDeadlineAt();
                    r.setSetupCompletionDeadlineAt(command.at());
                } else if (r.getInventoryRemovalPublishedAt() != null) {
                    before = r.getInventoryRemovalDeadlineAt();
                    r.setInventoryRemovalDeadlineAt(command.at());
                    returns.findByRental_Id(r.getId()).stream()
                        .filter(rc -> List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(rc.getStatus()))
                        .forEach(rc -> { rc.setRecoveryEligibleAt(command.at()); returns.save(rc); });
                } else if (r.getInventoryRemainderDeadlineAt() != null) {
                    throw ApiExceptions.conflict("Hồ sơ dùng phương án cũ. Vui lòng xử lý kết quả kiểm kê trước khi dời hạn.");
                } else {
                    before = r.getInventoryFinalDeadlineAt();
                    r.setInventoryFinalDeadlineAt(command.at());
                }
                if (before != null && !command.at().isAfter(before)) throw ApiExceptions.validation("Hạn mới phải sau hạn hiện tại.", null);
            }
            case "REMIND" -> {
                if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER)) throw ApiExceptions.forbidden("Không có quyền nhắc khách.");
                scope.assertCanOperate(actor, r.getFacility().getId());
                String key = "REMIND_" + (actor.hasRole(RoleCode.MANAGER) ? "MANAGER" : "STAFF");
                for (Map<String,Object> h : history(r)) {
                    if (key.equals(h.get("action")) && Instant.parse((String)h.get("time")).plus(2, ChronoUnit.HOURS).isAfter(now))
                        throw ApiExceptions.conflict("Vui lòng chờ đủ 2 giờ trước khi nhắc lại.");
                }
                append(r, actor, key, null, null, null, now);
                notifications.createNotification(r.getCustomer().getId(), NotificationType.CHECKIN,
                    "Nhắc lịch kiểm kê", "Vui lòng mở hồ sơ để kiểm tra lịch hẹn và thời hạn kiểm kê.", reservationId);
                rentals.saveAndFlush(r);
                return view(r);
            }
            default -> throw ApiExceptions.validation("Thao tác không hợp lệ.", null);
        }
        append(r, actor, command.action(), before, command.at(), command.reason(), now);
        rentals.saveAndFlush(r);
        String title = "APPOINTMENT".equals(command.action())
            ? (before == null ? "Đã đặt lịch kiểm kê" : "Đã đổi lịch kiểm kê")
            : "Hồ sơ nhận kho đã cập nhật";
        String content = "APPOINTMENT".equals(command.action())
            ? "Đơn " + r.getReservation().getReservationCode() + " có lịch kiểm kê lúc "
                + java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                    .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(command.at())
                + " (giờ Việt Nam)."
            : "Lịch hẹn hoặc tiến trình xử lý đã được cập nhật. Vui lòng xem chi tiết hồ sơ.";
        notifications.createNotification(r.getCustomer().getId(), NotificationType.CHECKIN, title, content, reservationId);
        notifications.createFacilityOperationsNotifications(r.getFacility().getId(), NotificationType.CHECKIN,
            title, content, reservationId);
        return view(r);
    }

    private void access(ActorPrincipal actor, Rental r) {
        if (actor.hasRole(RoleCode.CUSTOMER)) { customer(actor, r); return; }
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Không có quyền xem hồ sơ.");
        scope.assertCanRead(actor, r.getFacility().getId());
    }
    private void customer(ActorPrincipal actor, Rental r) {
        if (!actor.hasRole(RoleCode.CUSTOMER) || !actor.userId().equals(r.getCustomer().getId()))
            throw ApiExceptions.forbidden("Không có quyền thao tác hồ sơ này.");
    }
    private void staff(ActorPrincipal actor, Rental r) {
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận buổi kiểm kê.");
        scope.assertCanOperate(actor, r.getFacility().getId());
    }
    private List<Map<String,Object>> history(Rental r) {
        try { return r.getWorkflowHistoryJson() == null ? new ArrayList<>() : json.readValue(r.getWorkflowHistoryJson(), new TypeReference<List<Map<String,Object>>>(){}); }
        catch (Exception e) { throw ApiExceptions.conflict("Lịch sử hồ sơ cần được đối soát."); }
    }
    private void append(Rental r, ActorPrincipal actor, String action, Instant before, Instant after, String reason, Instant now) {
        var list = history(r);
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("action", action); event.put("actor", actor.userId().toString()); event.put("time", now.toString());
        event.put("before", before == null ? null : before.toString()); event.put("after", after == null ? null : after.toString()); event.put("reason", reason);
        list.add(event);
        try { r.setWorkflowHistoryJson(json.writeValueAsString(list)); }
        catch (Exception e) { throw new IllegalStateException("Cannot save workflow history", e); }
    }
    private Map<String,Object> view(Rental r) {
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("setupDeadlineAt", r.getSetupCompletionDeadlineAt()); v.put("setupExtensionDays", r.getSetupExtensionDays());
        v.put("setupExtensionCount", r.getSetupExtensionCount()); v.put("readyAt", r.getMoveInCompletedAt());
        var checkIn = checkIns.findByReservation_Id(r.getReservation().getId()).orElse(null);
        Instant appointmentAt = r.getInventoryAppointmentAt() != null
            ? r.getInventoryAppointmentAt()
            : checkIn == null ? null : checkIn.getInventoryAppointmentAt();
        v.put("appointmentAt", appointmentAt); v.put("arrivedAt", r.getInventoryArrivedAt());
        v.put("identityVerifiedAt", r.getInventoryIdentityVerifiedAt()); v.put("inventoryDeadlineAt", r.getInventoryFinalDeadlineAt());
        v.put("resultPublishedAt", r.getInventoryResultPublishedAt()); v.put("remainderDeadlineAt", r.getInventoryRemainderDeadlineAt());
        v.put("resultConfirmationDueAt", checkIn != null && checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.PENDING_CUSTOMER
            && r.getInventoryResultPublishedAt() != null ? r.getInventoryResultPublishedAt().plus(48, ChronoUnit.HOURS) : null);
        v.put("history", history(r));
        return v;
    }
}
