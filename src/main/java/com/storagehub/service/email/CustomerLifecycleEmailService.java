package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import com.storagehub.domain.model.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerLifecycleEmailService {
    private final EntityManager em;
    private final TransactionalEmailService mail;
    private final EmailProperties properties;
    private final com.storagehub.service.rental.period.RentalPeriodResolver periods;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public void notification(Notification notice) {
        User user = notice.getUser();
        if (user.getRoles().stream().noneMatch(role -> role.getCode() == RoleCode.CUSTOMER)) return;
        // System messages may include promotions; those need a separate consent/unsubscribe flow.
        if (notice.getType() == NotificationType.SYSTEM) return;
        if (notice.getType() == NotificationType.PAYMENT && notice.getTitle().equals("Thanh toán thành công")) return;
        UUID resource = notice.getRelatedEntityId();
        Reservation reservation = resource == null ? null : em.find(Reservation.class, resource);
        Rental rental = null;
        ReturnCase returned = null;
        if (reservation == null && resource != null && notice.getType() == NotificationType.CHECKIN) {
            CheckIn checkIn = em.find(CheckIn.class, resource);
            if (checkIn != null) reservation = checkIn.getReservation();
        }
        if (reservation == null && resource != null && notice.getType() == NotificationType.PAYMENT) {
            returned = em.find(ReturnCase.class, resource);
            if (returned != null) rental = returned.getRental();
        }
        if (reservation == null && resource != null) {
            if (notice.getType() == NotificationType.RETURN) {
                returned = em.find(ReturnCase.class, resource);
                if (returned != null) rental = returned.getRental();
            } else if (rental == null && (notice.getType() == NotificationType.RENEWAL || notice.getType() == NotificationType.PAYMENT)) {
                Renewal renewal = em.find(Renewal.class, resource);
                if (renewal != null) rental = renewal.getRental();
                else {
                    Payment payment = em.find(Payment.class, resource);
                    if (payment != null) {
                        reservation = payment.getReservation();
                        if (payment.getRenewal() != null) rental = payment.getRenewal().getRental();
                    }
                }
            }
            if (rental == null) rental = em.find(Rental.class, resource);
        }
        if (rental == null && reservation != null && notice.getType() == NotificationType.CHECKIN) {
            rental = em.createQuery("select r from Rental r where r.reservation.id=:id", Rental.class)
                .setParameter("id", reservation.getId()).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
        }
        if (rental != null && !rental.getCustomer().getId().equals(user.getId())) rental = null;
        if (rental != null) reservation = rental.getReservation();
        if (reservation != null && !reservation.getCustomer().getId().equals(user.getId())) reservation = null;
        Map<String, String> details = reservation == null ? new LinkedHashMap<>() : reservationDetails(reservation);
        if (reservation == null && resource != null) details.put("Mã hồ sơ liên quan", resource.toString());
        if (rental != null) {
            details.put("Ngày bắt đầu thuê", rental.getStartDate().toString());
            periods.read(rental).ifPresent(period -> details.put("Ngày cuối được sử dụng", period.lastPermittedDate().toString()));
            if (rental.getSetupStartedAt() != null) details.put("Bắt đầu chuẩn bị", TIME.format(rental.getSetupStartedAt()));
            if (rental.getSetupEndsAt() != null) details.put("Kết thúc chuẩn bị", TIME.format(rental.getSetupEndsAt()));
        }
        if (returned != null && returned.getCustomer().getId().equals(user.getId())) {
            details.put("Lịch kiểm tra/trả kho dự kiến", returned.getScheduledDate().toString());
            if (returned.getInspectedAt() != null || returned.getReviewedAt() != null || returned.getSettlementPaidAt() != null) {
                details.put("Tổng khấu trừ theo bảng kê", money(returned.getTotalDeductions()));
                details.put("Tiền hoàn theo bảng kê", money(returned.getNetRefundAmount()));
                details.put("Phần khách còn phải thanh toán", money(returned.getAmountDueFromCustomer()));
            }
            if (returned.getManagerResolutionNote() != null && !returned.getManagerResolutionNote().isBlank()) {
                details.put("Lý do / phản hồi quyết toán", safeSummary(returned.getManagerResolutionNote()));
            }
            if (returned.getCompletedAt() != null) details.put("Hoàn tất hồ sơ", TIME.format(returned.getCompletedAt()));
            if (returned.getSettlementPaidAt() != null) details.put("Ghi nhận chuyển/thu tiền", TIME.format(returned.getSettlementPaidAt()));
            if (returned.getSettlementPaymentId() != null) details.put("Chứng từ đối chiếu", "Xem mã chứng từ và biên lai trong hồ sơ trên web.");
            if (returned.getStatus() == ReturnCaseStatus.refund_pending && returned.getRefundExpectedAt() != null) {
                details.put("Hạn xử lý hoàn tiền dự kiến", TIME.format(returned.getRefundExpectedAt()));
                details.put("Lưu ý hoàn tiền", "Đã duyệt chưa đồng nghĩa đã chuyển. Khi chuyển xong, hồ sơ sẽ cập nhật chứng từ; thời gian tiền vào tài khoản phụ thuộc ngân hàng.");
            }
            if (returned.getStatus() == ReturnCaseStatus.refund_pending && returned.getRefundExpectedAt() == null) {
                details.put("Hạn xử lý hoàn tiền dự kiến", "Hồ sơ cũ chưa có mốc xác nhận; vui lòng liên hệ cơ sở để được cập nhật.");
            }
        }
        String section = notice.getType() == NotificationType.RESERVATION || (notice.getType() == NotificationType.CHECKIN && rental == null) ? "reservations" : "rental-records";
        send(user, "notification:" + notice.getId(), notice.getTitle(), safeSummary(notice.getContent()), details, section, reservation == null ? null : reservation.getId(), null);
    }

    public void reservationCreated(Reservation reservation) {
        boolean review = reservation.getStatus() == ReservationStatus.AWAITING_REVIEW;
        send(reservation.getCustomer(), "reservation-created:" + reservation.getId(), "Đã tạo yêu cầu giữ kho",
            review ? "Hồ sơ cần nhân viên duyệt hàng hóa trong tối đa 6 giờ. Sau khi được duyệt, bạn có thời hạn thanh toán cọc riêng hiển thị trên đơn."
                : "Suất kho đang được giữ tạm đến thời hạn bên dưới. Mở đơn để thanh toán cọc. Chỉ sau khi cọc được xác nhận, đơn mới được xác nhận đặt thành công.",
            reservationDetails(reservation), "reservations", reservation.getId(), reservation.getHoldExpiresAt());
    }

    public void expiring(Reservation reservation) {
        send(reservation.getCustomer(), "hold-expiring:" + reservation.getId() + ":" + reservation.getPaymentExpiresAt(), "Đơn giữ kho sắp hết hạn thanh toán",
            "Còn tối đa 5 phút để thanh toán cọc. Nếu ngân hàng đã trừ tiền, hãy xem trạng thái và hướng dẫn đối soát trước khi thanh toán lại.",
            reservationDetails(reservation), "reservations", reservation.getId(), reservation.getPaymentExpiresAt());
    }

    public void billingReminder(Rental rental, com.storagehub.service.RentalReadSources.Financial financial, long remainingDays) {
        var details = reservationDetails(rental.getReservation());
        details.put("Tổng nghĩa vụ còn phải trả đã đối soát", money(financial.outstandingAmount()));
        if (financial.nextDueDate() != null) details.put("Hạn thanh toán tiếp theo", financial.nextDueDate().toString());
        send(rental.getCustomer(), "billing:" + rental.getId() + ":" + financial.nextDueDate() + ":" + remainingDays,
            remainingDays < 0 ? "Có khoản thanh toán quá hạn" : "Nhắc thanh toán trước hạn " + remainingDays + " ngày",
            "Vui lòng mở bảng kê khoản phải trả và biên lai trong hồ sơ. Chỉ các khoản phí có căn cứ theo điều khoản đã chấp nhận mới được áp dụng; trạng thái đối soát có thể cập nhật sau giao dịch ngân hàng.",
            details, "rental-records", rental.getReservation().getId(), null);
    }

    @Transactional
    public void facilityChanged(UUID facilityId, String key, String title, String summary) {
        Facility facility = em.find(Facility.class, facilityId);
        if (facility == null) return;
        var customers = em.createQuery("select distinct r.customer from Rental r where r.facility.id=:facility and r.actualReturnedAt is null and r.status in :statuses", User.class)
            .setParameter("facility", facilityId).setParameter("statuses", java.util.List.of(RentalStatus.setup, RentalStatus.active, RentalStatus.return_requested)).getResultList();
        for (User user : customers) send(user, "facility:" + facilityId + ":" + key, title, summary,
            Map.of("Cơ sở", facility.getName(), "Liên hệ cơ sở", facility.getPhone() == null ? "Xem thông tin cơ sở trên web" : facility.getPhone()), "rental-records", null, null);
    }

    private Map<String, String> reservationDetails(Reservation reservation) {
        var rows = new LinkedHashMap<String, String>();
        rows.put("Mã đơn giữ kho", reservation.getReservationCode());
        rows.put("Cơ sở", reservation.getFacility().getName());
        rows.put("Địa điểm", reservation.getFacility().getAddress());
        rows.put("Loại kho", reservation.getUnitType().getName());
        var unit = reservation.getUnitType();
        rows.put("Kích thước kho (D × R × C)", unit.getLengthM() + " × " + unit.getWidthM() + " × " + unit.getHeightM() + " m");
        if (reservation.getAssignedUnit() != null) rows.put("Mã gian kho", reservation.getAssignedUnit().getCode());
        if (reservation.getPaymentExpiresAt() != null) rows.put("Hạn thanh toán cọc", TIME.format(reservation.getPaymentExpiresAt()));
        else if (reservation.getHoldExpiresAt() != null) rows.put("Hạn giữ hồ sơ", TIME.format(reservation.getHoldExpiresAt()));
        if (reservation.getAppointmentAt() != null) rows.put("Lịch nhận kho", TIME.format(reservation.getAppointmentAt()));
        var checkIns = em.createQuery("select c from CheckIn c where c.reservation.id=:id", CheckIn.class).setParameter("id", reservation.getId()).setMaxResults(1).getResultList();
        if (!checkIns.isEmpty() && checkIns.getFirst().getAssignedStaff() != null) rows.put("Nhân viên phụ trách", checkIns.getFirst().getAssignedStaff().getFullName());
        rows.put("Giấy tờ khi nhận kho", "Mang giấy tờ định danh gốc trùng với hồ sơ và biên lai thanh toán; không gửi ảnh giấy tờ qua email.");
        return rows;
    }

    private void send(User user, String key, String title, String summary, Map<String, String> details, String section, UUID reservation, Instant expiry) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("fullName", user.getFullName()); vars.put("title", title); vars.put("summary", summary); vars.put("details", details);
        String base = properties.getFrontendBaseUrl().replaceAll("/+$", "");
        vars.put("detailsUrl", base + "/login?emailSection=" + section + (reservation == null ? "" : "&reservationId=" + reservation));
        if (expiry != null) vars.put("_expiresAt", expiry.toString());
        if (key.startsWith("hold-expiring:")) {
            vars.put("_reservationId", reservation.toString());
            vars.put("_requiredReservationStatus", "AWAITING_PAYMENT");
        }
        mail.queueTemplate(user.getId(), user.getEmail(), "StorageHub - " + title, "customer-lifecycle", vars, "CUSTOMER_TRANSACTIONAL_EMAIL_SENT", key);
    }

    private String safeSummary(String value) {
        if (value == null) return "Vui lòng mở hồ sơ trên web để xem chi tiết.";
        return value.replaceAll("(?iu)(mật khẩu|mã (?:ra vào|truy cập|ổ khóa)|số (?:tài khoản|thẻ)|CCCD|CMND)[^\\n.]*", "Thông tin bảo mật được cung cấp trong tài khoản StorageHub");
    }

    private String money(java.math.BigDecimal value) {
        return value == null ? "Chưa chốt" : java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("vi-VN")).format(value) + " đ";
    }
}
