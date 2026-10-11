# EligibilitySource và consumer operational guard — 11/10/2026

## Phạm vi/kết quả

Đã thêm adapter P05 read-only, nối guard hiện có vào D2/D3 và bổ sung test Rental trả kho/bảo trì/Recovery handoff/booking cam kết. Main compile PASS; bộ 10 class test liên quan **130 PASS, 0 failure/error/skipped**, BUILD SUCCESS lúc 11:53:59 Asia/Saigon, 57.980s. **Chưa chứng nhận positive eligibility runtime đầy đủ**: nguồn coverage Recovery/lịch ký còn thiếu, vẫn UNKNOWN trước cutoff. Không commit/push hoặc bật flag/module trong môi trường đang chạy.

Chỉ sửa consumer/presentation contract/test Dương; không sửa writer Booking/Return/Maintenance/Recovery, FE/UI, endpoint/DTO, permission/grant/security, schema hoặc Flyway. Không kết nối hoặc sửa MySQL/shared DB. Các integration tests dùng H2 in-memory fixture test profile create-drop độc lập; đây không phải đổi ddl-auto của BE/DB thật. start-mysql.bat/run-dev.* có sẵn được giữ nguyên ngoài scope.

## File và lý do

| File | Thay đổi |
| --- | --- |
| service/renewal/integration/SharedRenewalEligibilitySource.java | Adapter EligibilitySource, đọc guard/ngày chuẩn hóa/cutoff BO/coverage, fail closed |
| service/renewal/integration/RenewalEligibilityEvidenceSource.java | Boundary read-only cho complete Recovery/signing feasibility; không cài source giả |
| service/renewal/RenewalWorkflowService.java | Gọi guard tại gate options/quote/submit/edit/APPROVE/review trước policy và nguồn eligibility khác |
| service/renewal/operations/RenewalOperationService.java | Mọi SafetySource.check đi qua helper checkSafety, vẫn bắt buộc atomic shared safety; recheck sau tiền/trước complete |
| test/.../SharedRenewalEligibilitySourceTests.java | 13 case về ngày/BO/identity/current evidence/unknown/cutoff/errors và activation |
| test/.../RenewalOperationalGuardJpaTests.java | Thêm actualReturnedAt/completedAt dù status active; giữ 15 case guard cũ |
| test/.../RenewalWorkflowIntegrationTests.java | 4 case DB conflict sau quote/request; options/quote/submit/edit/approval blocked, không hold; replay/cancel còn dùng được |
| test/.../RenewalOperationIntegrationTests.java | 4 case conflict sau signing setup; cọc/completion blocked, không thêm money/consume/end-date |
| docs/RENEWAL_API_CONTRACT.md, RENEWAL_OPERATIONS_API_CONTRACT.md | Cập nhật cơ chế, activation và giới hạn, không đổi HTTP contract |

Không thay constructor consumer để phá fixture/caller hiện có: ObjectProvider guard được Spring inject bổ sung, chỉ đọc bean đã opt-in. D2 cancel/reject không qua gate mới; lệnh replay đã thành công vẫn trả kết quả đã nhớ sau authorization/context như trước. Các allowedActions D2 không chứa APPROVE khi operational gate bị chặn.

## Nguồn dữ liệu/semantics

- Guard giữ nguyên truy vấn nguồn thật: Rental status/actualReturnedAt/completedAt; StorageUnit cùng facility phải occupied; ReturnCase; MaintenanceTask open/in_progress; Rental khác chưa completed; Reservation khác assigned cùng unit chưa terminal; OverdueFollowUp RECOVERY_HANDOFF. Không hủy/đổi trạng thái nguồn. Bảo trì completed/cancelled, booking CANCELLED/EXPIRED/REJECTED/COMPLETED hoặc reservation của chính Rental không bị coi như conflict booking mới.
- Guard là **negative evidence**. Booking filter bảo thủ theo cam kết cùng unit, không cam kết phép tính availability theo package/range; occupied/sellable count không chứng minh đủ điều kiện nối kỳ. Recovery handoff còn chặn cho tới reconciliation; không có handoff không chứng minh không có Recovery bên ngoài.
- Adapter dùng RentalPeriodResolver (ngày inclusive/exclusive đã xác minh), OverdueSources.TermSource (PublishedRenewalPolicyAdapters dựa policy BO thật) và optional coverage. Missing dates/policy/coverage -> Optional.empty/UNKNOWN. Policy reference/version, lastPermittedDate, các ngưỡng và cutoff/start phải hợp lệ; inconsistent -> 409 DEFERRED_SOURCE. Không hardcode default cutoff/lịch ký.
- Tại/sau cutoff đã công bố -> snapshot verified bị chặn, không cần giả lập bằng chứng khả thi. Trước cutoff chỉ complete nếu coverage có đúng rentalId/unitId/facilityId và checkedAt bằng supplied server now; stale/future/foreign -> DEFERRED_SOURCE. API không nhận override eligibility từ client. DB/infrastructure exceptions propagate, không biến thành empty/success.
- Coverage interface yêu cầu nguồn có thẩm quyền composite Recovery và signing/calendar. **Chưa có runtime implementation**, unit tests sử dụng evidence TEST-ONLY. Port P05 có adapter wired nhưng vẫn PARTIAL/BLOCKED_POSITIVE_COVERAGE, không READY. Owner dữ liệu: flow Return/Recovery và lịch ký/capacity. Không cần permission review để nối source đọc, nhưng không thể tạo guarantee/data không tồn tại.
- Không implement hoặc giả định ExtensionHoldSource.participatesInTransaction, SafetySource.atomic, financial consistency, ApprovalLifecycleSource. Guard không khóa writer ngoài flow; snapshot địa phương không chứng nhận concurrency xuyên Booking/Return/Maintenance. Các gate atomic hiện có vẫn chặn trước approved/payment/completion khi chưa có nguồn đúng.

## Activation

`storagehub.integration.renewal-operational-checks.enabled=true` đăng ký guard và SharedRenewalEligibilitySource. Giữ default OFF hiện có; không tự sửa launcher/profiles để bật trên DB chung. Flag ON không biến missing coverage thành AVAILABLE và không cấp quyền/hold/calendar/tiền. Khi triển khai cần nguồn date/BO đúng, coverage thật và kiểm chứng atomic protocol; báo cáo này không phải runtime/E2E certification. Không đăng ký thêm EligibilitySource trùng bean mà không thống nhất selection.

## Test/bằng chứng

| Bộ test | PASS |
| --- | ---: |
| RenewalGuardActivationAndScopeTests | 6 |
| RenewalOperationalGuardJpaTests | 17 |
| SharedRenewalEligibilitySourceTests | 13 |
| RenewalExceptionProposalTests | 24 |
| RenewalOperationIntegrationTests | 29 |
| RenewalPersistenceTests | 6 |
| RenewalPreparationTests | 8 |
| RenewalWorkflowIntegrationTests | 19 |
| InclusiveRenewalCompatibilityTests | 2 |
| RenewalDateConsumerTests | 6 |
| Tổng | 130 |

23 case mới (13 adapter, 2 returned/completed timestamp, 4 D2, 4 D3), 107 case regression. Các test concurrency hiện có chạy trong suite, không coi đó là chứng nhận shared-writer protocol ngoài flow. Integration tests persist conflict vào H2, reload/check qua consumer, không dùng fake API fallback runtime. Test provider tài chính/hold/calendar là fixture TEST-ONLY.

Main command: `mvnw.cmd -Dmaven.test.skip=true compile`, 426 sources, BUILD SUCCESS 11:49:49. Do full testCompile có blocker Customer cũ, javac --release21 compile riêng 10 test class và chạy `mvnw.cmd -Dtest=<10 class above> surefire:test`. Không skip/delete/weakening test Customer hoặc dùng source runtime cũ. Log `%TEMP%/swp-eligibility-compile-20261011.log`, `%TEMP%/swp-eligibility-tests-final-20261011.log`.

Baseline lần mới: fixture unit test gọi setId không tồn tại -> sửa riêng bằng ReflectionTestUtils cho TEST-ONLY IDs. Lượt suite đầu 130 run/1 failure/1 error: booking fixture code dài 42 > VARCHAR32 và D3 returned message đã chặn ở terms trước local guard. Đã sửa code fixture ngắn, assert đúng lý do chặn existing; không sửa Entity/schema hoặc bỏ assert về money/end-date. Lượt final 130 PASS như trên.

Kiểm lại `mvnw.cmd test-compile` sau implementation: BLOCKED/FAIL với đúng 7 lỗi baseline trong `CustomerReservationServiceTests.java` (constructor cũ) và `CustomerReceiptPeriodCompatibilityTests.java` (constructor/confirmReceipt cũ). Không phát sinh lỗi compile test mới; log `%TEMP%/swp-eligibility-full-testcompile-20261011.log`. Không tuyên bố full BE suite PASS hoặc xóa test cũ để làm suite xanh.

BE scope `git diff --check` PASS. Thay đổi chỉ ở 4 file Java runtime mới/sửa, 4 file test mới/sửa và 3 tài liệu; script local vẫn ngoài scope, không stage/commit/push. HEAD BE vẫn 408aee1; FE vẫn merge 9866368 từ task trước, không sửa FE ở task này. Browser, MySQL integration và runtime API mode không chạy trong task này; status NOT_VERIFIED, không E2E PASS.
