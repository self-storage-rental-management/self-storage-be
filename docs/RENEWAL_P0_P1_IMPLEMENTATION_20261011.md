# Bổ sung Eligibility, Extension Hold và ngày policy — 11/10/2026

Baseline source: BE feature/be-duong, 0dbef00. Thực hiện theo yêu cầu P0/P1, giữ chặn khi thiếu nguồn. Không commit/push hoặc thay DB thật trong lượt này.

## Trạng thái thực tế

| Mục | Implementation | Runtime/E2E |
| --- | --- | --- |
| P0 Eligibility evidence | Runtime composite adapter đã có; kiểm tra Recovery binding/freshness và calendar feasibility dưới cutoff hiện hành | BLOCKED: chưa có receiver Recovery đầy đủ và CalendarSource thực |
| P0 Extension hold | JDBC persistence + runtime D2 ExtensionHoldSource/D3 SafetySource; transaction, unique replay, lock Rental/Unit/hold, rollback, retain/consume/release | BLOCKED: chưa có shared-writer protocol runtime; chưa rollout migration MySQL |
| P1 ngày policy | Giữ quy tắc được người dùng chọn, tách ngày áp dụng hai nhóm; thêm regression effectiveFrom/effectiveTo và múi giờ Việt Nam | Không tự hợp nhất policy/đổi cutoff; chưa browser E2E |

**Không coi hai P0 đã được giải quyết hoàn toàn.** Có adapter không đồng nghĩa có dữ liệu dương tính hoặc APPROVE chạy được. Atomic/participatesInTransaction chỉ true khi có protocol thực với ready=true; không cung cấp implementation mặc định. Lifecycle/financial consistency/accounting vẫn là các gate riêng.

## Bằng chứng tích cực

SharedRenewalEligibilityEvidenceSource được đăng ký cùng flag operational checks. Đọc receiver-owned RecoverySource.state(Rental,now): rental/facility/checkedAt phải đúng context và chính xác server instant. CalendarSource.atomic và overload feasible(Rental,now,cutoff) phải cung cấp dữ liệu thật từ giờ làm việc, thời lượng, lịch/capacity đã giữ.

Defaults của hai overload là Optional.empty, tương thích implementation cũ nhưng không giả vờ đã nối đầy đủ. Recovery blocked và feasible=false vẫn phân biệt với UNKNOWN. Không suy Recovery clear từ không có RECOVERY_HANDOFF, không tạo Renewal transient giả, không suy lịch ký từ số gian trống. Lỗi provider không bị nuốt.

## Hold persistence và D3

Một runtime adapter triển khai cả hai port hiện có; consumer đã lấy ObjectProvider theo type nên không sửa endpoint, request/response hoặc handler. Hai flag phải đồng thời bật mới đăng ký hold. Không bật flag/config trong task.

SQL table renewal_extension_holds, UUID binary16, unique renewal_id, 4 FK và index unit/state/date. Initial expiry và expiry hiện tại tách riêng: replay acquire kiểm tra payload gốc kể cả sau retain. Timestamp canonical microseconds phù hợp TIMESTAMP(6).

Writable caller transaction bắt buộc, không REQUIRES_NEW hoặc remote side effect. JPA khóa/refresh Rental → Unit; xác nhận JdbcTemplate datasource bound vào caller transaction; SQL hold row và overlap dùng FOR UPDATE để tránh snapshot cũ. Conflict cùng unit được serialize trong test.

ACTIVE đã hết hạn vẫn block overlap đến khi caller đối soát payment rồi release rõ ràng. Release không đổi trạng thái vật lý unit, Recovery, access hoặc tiền. Consume chỉ đánh dấu hold, consumer D3 hiện hữu mới kéo dài Rental sau tài chính/ký/evidence checks. Replay consume/release và retain cùng deadline không đổi version thêm. Không mở lại reference terminal hoặc dùng hold của renewal khác.

RenewalSharedWriterProtocol là boundary **chưa có runtime implementation**, không phải chứng nhận protocol đã hoàn thiện. ready() mặc định false. lockAndCheck phải re-read Recovery, tài chính/capacity hiện hành, cùng lock order và shared writers trước khi trả cutoff thật. TestProtocol chỉ nằm trong test.

Migration V2026101104 ở db/renewal-hold-migration, **không trong default Flyway locations**, không có Entity mới nên DB hiện tại không bị schema validate yêu cầu thêm bảng khi tắt module. Chưa áp dụng migration lên MySQL/TiDB/local/shared DB. SQL additive không sửa bảng/history/checksum có sẵn.

## Ngày áp dụng đã thống nhất

Người dùng chọn giữ cutoff theo policy quá hạn hiện hành:

- D2 request/pricing/payment và D3 signing: applicableDate = period.endExclusive(), tức ngày bắt đầu kỳ gia hạn.
- Eligibility recovery cutoff và D4 term: applicableDate = server now ở Asia/Ho_Chi_Minh.
- effectiveFrom và effectiveTo đều inclusive. effectiveTo=null không giới hạn; date=null chỉ phục vụ readback.
- Future-only publication không được dùng làm policy quá hạn hôm nay; thiếu current term giữ UNKNOWN, dù policy extension tồn tại.

Publication hiện chỉ lưu một JSON snapshot mỗi cơ sở. Không tự bổ sung lịch sử policy/đè policy hiện hành hoặc fallback dùng future policy. Vì vậy publish future-only thay snapshot hiện tại có thể làm current cutoff thiếu nguồn; việc giữ nhiều phiên bản cùng lúc là gap riêng, cần thiết kế BO publication lifecycle/history.

## Dependency còn BLOCKED

| Dependency | Nguồn/owner chức năng cần bổ sung | Điều kiện mở |
| --- | --- | --- |
| Recovery current state | Receiver Recovery/operations; hiện không có class runtime receiver | Có dữ liệu có thẩm quyền bao phủ cả Recovery bên ngoài, đúng rental/facility/time; absence handoff không đủ |
| Signing calendar | Scheduling/operations + policy BO | Có giờ làm việc, ngày nghỉ, duration, staff/capacity/reservation thật và pre-approval feasibility |
| Cross-writer protocol | Booking/Assignment/Return/Maintenance/Recovery/Payment | Cùng lock order và hold-aware current reads/writes, có concurrency/rollback regression từng writer |
| Hold rollout | Database deployment | MySQL test migration fresh/upgrade, FK/index/check, không reset; chọn location đúng history |
| Full test compile | CustomerReservationServiceTests và CustomerReceiptPeriodCompatibilityTests | Cập nhật constructor/confirmReceipt test cũ theo develop, không xóa hoặc skip test |

Các writer cần review cụ thể gồm UnitAssignmentService, MaintenanceService, ReturnSettlementService, CheckInHandoverService, CancelledReservationUnitReleaseService, FacilityManagementService, CustomerReservationService và nguồn Payment/Recovery. Chưa sửa các class đó; tên người owner chưa xác nhận. Không bật ready khi một writer có thể bỏ qua hold.

## Kiểm thử

| Kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- |
| Main compile | PASS | 429 sources, BUILD SUCCESS, 12:22:17 +07 |
| Selected renewal suite | PASS | 164 run, 0 failures/errors/skipped; BUILD SUCCESS 12:23:22 +07 |
| Composite evidence | PASS | 14 test mới: coverage, stale/foreign, unavailable/non-atomic, no-fake flag activation |
| Policy date boundaries | PASS | 7 test mới, gồm future-only/current cutoff và midnight Việt Nam |
| Persisted hold | PASS | 13 test mới: actual SQL/FK, retry, precision, D3 check, lifecycle, rollback, competing/concurrent acquire |
| Existing renewal regressions | PASS | 130 case trước đó, giữ nguyên behavior assertions |
| Full test-compile | FAIL/BLOCKED | 7 lỗi baseline ở hai test Customer constructor/confirmReceipt cũ |
| Whitespace/diff scope | PASS | git diff --check cho tracked scope; no-index --check cho 9 file mới |
| MySQL/TiDB rollout, browser E2E, writer interoperability | NOT_VERIFIED/BLOCKED | Không thực hiện trong task |

Command main: `mvnw.cmd -Dmaven.test.skip=true compile`. Full compile probe: `mvnw.cmd test-compile`. Do probe vướng baseline, compile rõ 13 source test bằng `javac --release 21 -parameters -cp target/classes;target/test-classes;<Maven test classpath> -d target/test-classes <13 sources>`, rồi `mvnw.cmd -Dtest=SharedRenewalEligibilitySourceTests,RenewalOperationalGuardJpaTests,RenewalGuardActivationAndScopeTests,RenewalWorkflowIntegrationTests,RenewalOperationIntegrationTests,RenewalDateConsumerTests,InclusiveRenewalCompatibilityTests,RenewalExceptionProposalTests,RenewalPreparationTests,RenewalPersistenceTests,SharedRenewalEligibilityEvidenceSourceTests,PublishedRenewalPolicyDateTests,TransactionalRenewalExtensionHoldTests surefire:test`.

Tất cả persistence tests chạy H2 test-only, không dùng mock thay response runtime hoặc fake provider production. Full test-compile không được báo PASS, không xóa/skip test Customer để che lỗi.

Logs TEMP: swp-renewal-p0-compile-20261011.log, swp-renewal-p0-testcompile-20261011.log, swp-renewal-p0-selected-javac-20261011.log, swp-renewal-p0-tests-final-20261011.log.

Lượt đầu suite 163 test có 2 lỗi hold test do H2 2.4 IN CHECK cache session DDL đã đóng trong ca nhiều connection. Đổi IN thành OR không giải quyết vì H2 tối ưu về IN; đã giữ nguyên constraint production và sửa fixture giữ connection tạo schema đến cuối test. Không bỏ assert/constraint. Rerun hold/evidence 27/27 PASS, bao gồm rollback và concurrent acquire; không coi đó là certification writer bên ngoài.

## Phạm vi và giới hạn

Source mới: SharedRenewalEligibilityEvidenceSource, TransactionalRenewalExtensionHoldSource, RenewalSharedWriterProtocol. Bổ sung default methods vào OverdueSources/CalendarSource, tách hàm inclusive applicability của policy (không đổi semantics), thêm 3 test class và điều chỉnh activation test cũ.

Không sửa FE/UI, shared security, tiền/ledger, booking/check-in/return/maintenance writer, schema đang chạy hoặc migration cũ. Script local của người dùng giữ nguyên ngoài task. Không mock/fallback production. Không chạy real-money payment, API mutation hoặc browser E2E; NOT_VERIFIED cho MySQL rollout và cross-writer protocol.
