package com.storagehub.service.rental;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.NotificationService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalContractLifecycleService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final RentalRepository rentalRepository;
    private final ContractRepository contractRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;
    private final EntityManager em;
    private final ObjectProvider<Clock> clocks;

    public record LifecycleExecutionSummary(
        int expirationsEvaluated,
        int contractsMarkedExpired,
        int preExpirationRemindersSent,
        int handoverAlertsCreated,
        int latePaymentRemindersDMinus3,
        int overdueNoticesDPlus1,
        int gracePeriodEndedWatchListDPlus3,
        int managerEscalationsDPlus7,
        Instant executedAt
    ) {}

    public record PostExpirationEligibility(
        UUID rentalId,
        boolean eligible,
        String reason,
        UUID storageUnitId,
        String storageUnitCode,
        LocalDate contractEndDate,
        ContractStatus contractStatus
    ) {}

    public Instant now() {
        Clock c = clocks.getIfAvailable();
        return (c == null ? Clock.system(ZONE) : c).instant();
    }

    public LocalDate today() {
        return now().atZone(ZONE).toLocalDate();
    }

    /**
     * Pillar 3: Process Contract Expirations, Pre-expiration reminders, and Unit Handover tracking.
     */
    @Transactional
    public LifecycleExecutionSummary processContractExpirations() {
        LocalDate currentDay = today();
        Instant currentInstant = now();

        int evaluated = 0;
        int markedExpired = 0;
        int preExpReminders = 0;
        int handoverAlerts = 0;

        List<Rental> activeRentals = rentalRepository.findByStatus(RentalStatus.active);

        for (Rental rental : activeRentals) {
            evaluated++;
            LocalDate endDate = rental.getContractEndDate();
            if (endDate == null) {
                continue;
            }

            long daysUntilExpiry = ChronoUnit.DAYS.between(currentDay, endDate);
            User customer = rental.getCustomer();
            StorageUnit unit = rental.getStorageUnit();
            Facility facility = rental.getFacility();
            String unitCode = unit != null ? unit.getCode() : "N/A";
            String facilityName = facility != null ? facility.getName() : "N/A";

            // Milestone 1: D-14 Pre-expiration reminder (within 14 days window)
            if (daysUntilExpiry == 14) {
                String title = "Nhắc nhở: Hợp đồng thuê kho sắp hết hạn (còn 14 ngày)";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), rental.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.SYSTEM,
                        title,
                        String.format("Hợp đồng thuê kho %s tại %s sẽ hết hạn vào ngày %s. Quý khách có thể yêu cầu gia hạn ngay hôm nay.",
                            unitCode, facilityName, endDate),
                        rental.getId()
                    );
                    preExpReminders++;
                }
            }

            // Milestone 2: D-3 Pre-expiration reminder (3 days before expiration)
            if (daysUntilExpiry == 3) {
                String title = "Cảnh báo: Hợp đồng thuê kho sắp hết hạn (còn 3 ngày)";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), rental.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.SYSTEM,
                        title,
                        String.format("Hợp đồng thuê kho %s tại %s sẽ hết hạn vào ngày %s. Vui lòng xác nhận gia hạn hoặc chuẩn bị bàn giao kho.",
                            unitCode, facilityName, endDate),
                        rental.getId()
                    );
                    preExpReminders++;
                }
            }

            // Milestone 3: Contract Expiration (currentDay is after contractEndDate)
            if (currentDay.isAfter(endDate)) {
                // Find or synchronize Contract entity
                List<Contract> contracts = contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rental.getId());
                Contract contract = contracts.isEmpty() ? null : contracts.get(0);

                if (contract != null && contract.getStatus() == ContractStatus.SIGNED) {
                    contract.setStatus(ContractStatus.EXPIRED);
                    contract.setExpiresAt(endDate.atStartOfDay(ZONE).toInstant());
                    contractRepository.save(contract);
                    markedExpired++;

                    auditLogService.recordMutation(
                        "CONTRACT_EXPIRED",
                        "Contract",
                        contract.getId(),
                        facility != null ? facility.getId() : null,
                        Map.of("status", ContractStatus.SIGNED),
                        Map.of("status", ContractStatus.EXPIRED, "expiredOn", endDate.toString())
                    );
                }

                // Customer expiration notification
                String expTitle = "Thông báo: Hợp đồng thuê kho đã hết hạn";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), rental.getId(), expTitle)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.SYSTEM,
                        expTitle,
                        String.format("Hợp đồng thuê kho %s tại %s đã hết hạn vào ngày %s. Quý khách vui lòng liên hệ ban quản lý để bàn giao kho hoặc hoàn tất thủ tục liên quan.",
                            unitCode, facilityName, endDate),
                        rental.getId()
                    );
                }

                // Check unit handover status: if warehouse unit has not been handed over yet
                if (rental.getActualReturnedAt() == null && rental.getStatus() == RentalStatus.active) {
                    String alertTitle = "CẢNH BÁO BÀN GIAO: Hợp đồng hết hạn nhưng kho chưa được bàn giao";
                    // Alert facility staff/manager
                    if (facility != null) {
                        List<User> managersAndStaff = userRepository.findByFacilityScope(
                            facility.getId(),
                            EnumSet.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
                        );
                        for (User staffUser : managersAndStaff) {
                            if (!notificationRepository.existsMilestone(staffUser.getId(), rental.getId(), alertTitle)) {
                                notificationService.createNotification(
                                    staffUser.getId(),
                                    NotificationType.SYSTEM,
                                    alertTitle,
                                    String.format("Kho %s tại %s của khách hàng %s đã hết hạn vào ngày %s nhưng chưa được bàn giao. Cần theo dõi kiểm tra hiện trạng.",
                                        unitCode, facilityName, customer != null ? customer.getFullName() : "N/A", endDate),
                                    rental.getId()
                                );
                                handoverAlerts++;
                            }
                        }
                    }
                }
            }
        }

        return new LifecycleExecutionSummary(
            evaluated, markedExpired, preExpReminders, handoverAlerts,
            0, 0, 0, 0, currentInstant
        );
    }

    /**
     * Pillar 4: Process Late Payment Milestones (D-3, D+1, D+3, D+7).
     * Rule: 3-day grace period does NOT modify contract end date.
     * Late payment is tracked separately from contract status.
     * Does NOT auto-lock, terminate, or penalize without authorized policy.
     */
    @Transactional
    public LifecycleExecutionSummary processLatePayments() {
        LocalDate currentDay = today();
        Instant currentInstant = now();

        int dMinus3Count = 0;
        int dPlus1Count = 0;
        int dPlus3Count = 0;
        int dPlus7Count = 0;

        // Query pending payments with deadline
        List<Payment> pendingPayments = em.createQuery("""
            select p from Payment p
            join fetch p.initiatedBy
            left join fetch p.reservation r
            left join fetch r.facility
            where p.status = com.storagehub.domain.model.PaymentStatus.PENDING
              and p.paymentDeadline is not null
            """, Payment.class).getResultList();

        for (Payment payment : pendingPayments) {
            LocalDate dueDate = payment.getPaymentDeadline().atZone(ZONE).toLocalDate();
            long diff = ChronoUnit.DAYS.between(dueDate, currentDay);
            User customer = payment.getInitiatedBy();
            BigDecimal amount = payment.getAmount();
            String currency = payment.getCurrency() != null ? payment.getCurrency() : "VND";

            // Milestone D-3: 3 calendar days before due date (diff == -3)
            if (diff == -3) {
                String title = "Nhắc nhở thanh toán: Khoản phí sắp đến hạn (D-3)";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), payment.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.PAYMENT,
                        title,
                        String.format("Khoản thanh toán số tiền %s %s của quý khách sẽ đến hạn vào ngày %s. Quý khách vui lòng thanh toán đúng hạn.",
                            amount, currency, dueDate),
                        payment.getId()
                    );
                    dMinus3Count++;
                }
            }

            // Milestone D+1: 1 calendar day overdue (diff == 1)
            if (diff == 1) {
                String title = "Thông báo quá hạn: Khoản phí đến hạn thanh toán (D+1)";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), payment.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.OVERDUE,
                        title,
                        String.format("Khoản thanh toán số tiền %s %s đã quá hạn 1 ngày (hạn chót: %s). Quý khách vui lòng thanh toán trong thời gian gia hạn thanh toán 3 ngày lịch.",
                            amount, currency, dueDate),
                        payment.getId()
                    );
                    dPlus1Count++;
                }
            }

            // Milestone D+3: End of 3 calendar days grace period (diff == 3)
            if (diff == 3) {
                String title = "Thông báo: Kết thúc thời gian gia hạn thanh toán (D+3) - Đưa vào diện theo dõi";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), payment.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.OVERDUE,
                        title,
                        String.format("Thời gian gia hạn thanh toán 3 ngày lịch cho khoản phí %s %s (hạn gốc: %s) đã kết thúc. Khoản phải thu đã được đưa vào diện theo dõi nội bộ.",
                            amount, currency, dueDate),
                        payment.getId()
                    );
                    dPlus3Count++;
                }

                // Alert staff about watch list
                Facility fac = payment.getReservation() != null ? payment.getReservation().getFacility() : null;
                if (fac != null) {
                    List<User> staff = userRepository.findByFacilityScope(fac.getId(), EnumSet.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE));
                    String staffTitle = "THEO DÕI NỢ: Khoản phải thu quá hạn 3 ngày (Hết ân hạn)";
                    for (User s : staff) {
                        if (!notificationRepository.existsMilestone(s.getId(), payment.getId(), staffTitle)) {
                            notificationService.createNotification(
                                s.getId(),
                                NotificationType.OVERDUE,
                                staffTitle,
                                String.format("Khách hàng %s có khoản thanh toán %s %s quá hạn 3 ngày (hạn: %s). Đã đưa vào diện theo dõi.",
                                    customer != null ? customer.getFullName() : "N/A", amount, currency, dueDate),
                                payment.getId()
                            );
                        }
                    }
                }
            }

            // Milestone D+7: Extended overdue escalated to Manager review (diff >= 7)
            if (diff >= 7) {
                String title = "Cảnh báo quá hạn kéo dài (D+7): Chuyển hồ sơ Manager xem xét";
                if (customer != null && !notificationRepository.existsMilestone(customer.getId(), payment.getId(), title)) {
                    notificationService.createNotification(
                        customer.getId(),
                        NotificationType.OVERDUE,
                        title,
                        String.format("Khoản thanh toán số tiền %s %s đã quá hạn 7 ngày. Hồ sơ đã được chuyển đến cấp Quản lý (Manager) để xem xét theo quy định.",
                            amount, currency, dueDate),
                        payment.getId()
                    );
                    dPlus7Count++;
                }

                Facility fac = payment.getReservation() != null ? payment.getReservation().getFacility() : null;
                if (fac != null) {
                    List<User> managers = userRepository.findByFacilityScope(fac.getId(), EnumSet.of(FacilityScopeLevel.MANAGE));
                    String managerTitle = "CẦN MANAGER XEM XÉT (D+7): Hồ sơ quá hạn thanh toán kéo dài";
                    for (User m : managers) {
                        if (!notificationRepository.existsMilestone(m.getId(), payment.getId(), managerTitle)) {
                            notificationService.createNotification(
                                m.getId(),
                                NotificationType.OVERDUE,
                                managerTitle,
                                String.format("Khách hàng %s có khoản phải thu %s %s đã quá hạn %d ngày (hạn gốc: %s). Cần Manager xem xét hướng xử lý theo hợp đồng.",
                                    customer != null ? customer.getFullName() : "N/A", amount, currency, diff, dueDate),
                                payment.getId()
                            );
                        }
                    }
                }
            }
        }

        return new LifecycleExecutionSummary(
            0, 0, 0, 0,
            dMinus3Count, dPlus1Count, dPlus3Count, dPlus7Count, currentInstant
        );
    }

    /**
     * Pillar 2: Check renewal conditions when customer requests renewal after contract expiration.
     */
    @Transactional(readOnly = true)
    public PostExpirationEligibility checkPostExpirationRenewalEligibility(UUID rentalId) {
        Rental rental = rentalRepository.findById(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));

        List<Contract> contracts = contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rental.getId());
        Contract contract = contracts.isEmpty() ? null : contracts.get(0);
        ContractStatus contractStatus = contract != null ? contract.getStatus() : ContractStatus.SIGNED;

        StorageUnit unit = rental.getStorageUnit();
        UUID unitId = unit != null ? unit.getId() : null;
        String unitCode = unit != null ? unit.getCode() : "N/A";

        if (contractStatus == ContractStatus.TERMINATED) {
            return new PostExpirationEligibility(
                rentalId, false, "Hợp đồng đã bị chấm dứt trước hạn; không đủ điều kiện gia hạn",
                unitId, unitCode, rental.getContractEndDate(), contractStatus
            );
        }

        if (rental.getStatus() == RentalStatus.completed || rental.getActualReturnedAt() != null) {
            return new PostExpirationEligibility(
                rentalId, false, "Kho đã hoàn tất bàn giao và thanh lý hợp đồng; không thể gia hạn tiếp",
                unitId, unitCode, rental.getContractEndDate(), contractStatus
            );
        }

        // Check if unit is still assigned to this rental
        if (unit != null && unit.getStatus() != StorageUnitStatus.occupied) {
            return new PostExpirationEligibility(
                rentalId, false, "Kho không còn ở trạng thái khả dụng cho thuê tiếp",
                unitId, unitCode, rental.getContractEndDate(), contractStatus
            );
        }

        return new PostExpirationEligibility(
            rentalId, true, "Đủ điều kiện gia hạn sau hết hạn",
            unitId, unitCode, rental.getContractEndDate(), contractStatus
        );
    }

    /**
     * Formally terminate a contract early through approved process.
     */
    @Transactional
    public Contract terminateContract(ActorPrincipal actor, UUID contractId, String reason) {
        if (actor == null) {
            throw ApiExceptions.unauthorized("Authentication required");
        }
        Contract contract = contractRepository.findById(contractId)
            .orElseThrow(() -> ApiExceptions.notFound("Contract was not found"));

        if (contract.getStatus() == ContractStatus.TERMINATED) {
            throw ApiExceptions.conflict("Contract is already terminated");
        }

        ContractStatus previous = contract.getStatus();
        contract.setStatus(ContractStatus.TERMINATED);
        contract = contractRepository.saveAndFlush(contract);

        auditLogService.recordMutation(
            "CONTRACT_TERMINATED",
            "Contract",
            contract.getId(),
            contract.getRental().getFacility() != null ? contract.getRental().getFacility().getId() : null,
            Map.of("status", previous),
            Map.of("status", ContractStatus.TERMINATED, "reason", reason != null ? reason : "")
        );

        return contract;
    }
}
