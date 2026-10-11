# Xử lý migration BE sau merge develop — 11/10/2026

> BÁO CÁO LỊCH SỬ: phương án giữ chuỗi cũ dưới đây đã được thay thế theo phê duyệt ưu tiên develop. Chuỗi/location và kết quả hiện tại: [DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md](DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md). Các kết quả 74/6 test và 15 migration dưới đây chỉ thuộc lần kiểm chứng trước, không phải chứng nhận cho chuỗi mới.

## Phạm vi và điểm khôi phục

- Nhánh: `feature/be-duong`; merge `origin/develop` tại `b7ed639` với `--no-commit --no-ff`.
- HEAD trước merge: `8fafb75`; backup `backup/be-duong-before-migration-20261011-093607`.
- FE không merge hoặc sửa. Không commit/push kết quả lượt này.
- Script local (`start-mysql.bat`, `run-dev.*`, `run-rental-support-dev.ps1`) không sửa và đã được sao lưu ngoài repo.
- Chỉ sửa migration/các regression test liên quan; thêm import FileAssetStatus và bỏ một khai báo checkInRepository trùng trong CustomerReservationService để compile. Không sửa constructor/method/handler của file này.

## Bằng chứng baseline

Merge-tree FE/BE không có conflict Git, nhưng nguồn develop có duplicate Flyway versions 2026100909/2026101001 và hai SQL VNPay 0909/0910 giống nhau.

SELECT trên MySQL test 8.4.11 (`storagehub_schema_test_payment_full_b45468d5`) ghi nhận:

| Version | Script | Checksum |
| --- | --- | --- |
| 2026100909 | V2026100909__remove_legacy_rental_end_date.sql | -1449372527 |
| 2026101001 | V2026101001__create_rental_ledger_and_notification_outbox.sql | -1198625055 |
| 2026101101 | db.paymentmigration.V2026101101__add_payment_gateway_intent_id | NULL (Java migration cũ) |

Các source cũ trên giữ nguyên; không sửa checksum hoặc metadata đã applied.

Baseline compile sau merge FAIL: field checkInRepository trùng. Sau bỏ field, compile FAIL tiếp: thiếu import FileAssetStatus. Đã sửa riêng hai lỗi khai báo/import, không thay hành vi nghiệp vụ.

## Thay đổi migration

Ba SQL develop trùng được chuyển nguyên nội dung sang `src/main/resources/db/migration-archive/develop/`. Git blob các file archive khớp bản develop. Thư mục này không nằm trong location đang chạy.

Thêm `src/main/java/db/migration/V2026101102__align_vnpay_and_customer_checkin_schema.java`, được scan bởi location `classpath:db/migration`:

- Thêm 9 cột VNPay/refund/reconciliation của payments và appointment_at của reservations.
- Kiểm tra engine MySQL 8.4 và id BINARY(16) của hai bảng.
- Kiểm tra tất cả cột đã có về kiểu, độ dài, nullable, default và precision trước DDL; mismatch dừng, không ép kiểu hoặc ghi đè.
- Cột đúng giữ nguyên, cột thiếu mới được thêm; không update giao dịch, trạng thái, số tiền đã thu/hoàn hay lịch hẹn.
- Không repair/reset/baseline hoặc bật out-of-order. Payment gateway_intent_id V2026101101 vẫn opt-in ở db/paymentmigration.
- default common fresh chain: 14 migration; thêm Payment opt-in: 15 migration.
- refunded_amount DEFAULT 0 là cấu trúc theo Entity/VNPay hiện tại, không phải chứng nhận hồ sơ cũ chưa từng hoàn tiền. Ledger coverage/nguồn tài chính vẫn phải xác minh riêng; migration không mở các gate D1-D5 hoặc biến simulation thành thanh toán thật.

## Xác minh

Main source compile PASS sau hai sửa khai báo/import. Full `mvn test` hiện **BLOCKED ở testCompile**: `CustomerReservationServiceTests` và `CustomerReceiptPeriodCompatibilityTests` còn dùng constructor/confirmReceipt của flow cũ. Không xóa, skip hoặc thay assertion các test này để làm suite xanh.

Bộ migration được compile riêng bằng javac trên main classes hiện tại rồi chạy `surefire:test`: **74 PASS, 0 failure/error/skipped**, BUILD SUCCESS lúc 09:46:39 (55.759 s). Đây không phải full-suite PASS. Sáu test alignment mới kiểm fresh/version unique, upgrade giữ history/user, replay giữ transaction/refund/appointment values, schema một phần và mismatch/default sai dừng trước ALTER.

Baseline bộ migration riêng có 7 errors do fixture DB name vượt giới hạn MySQL 64 ký tự và gọi validate độc lập khi có pending migration. Đã sửa tên fixture và dùng validateOnMigrate (vẫn bật) rồi validate sau migrate; không ignore pending, không tắt validation. Chuỗi fresh full thực sự đã applied/validated 15 migration.

Lệnh kiểm thử:

```powershell
.\mvnw.cmd '-Dtest=DevelopMigrationAlignmentMySqlTests,LedgerMigrationSafetyCallbackTests,RentalSupportSchemaRolloutTests,RentalIntegrationMySqlSchemaTests,LedgerDefaultMigrationMySqlTests,LedgerMigrationRelocationMySqlTests,PaymentGatewayMigrationMySqlTests,LedgerStoreJdbcTests,NotificationOutboxJdbcTests' test
```

Lệnh trên hiện bị testCompile chung chặn. Lần PASS dùng `dependency:build-classpath` ghi classpath vào temp, javac compile 9 file test được liệt kê trong `-Dtest` vào target/test-classes, rồi gọi cùng selector với goal `surefire:test` (không gọi lifecycle test). Không thay test cũ; lớp main compile bằng Maven đã PASS, không chạy stale business logic để chứng nhận E2E.

Datasource phải là localhost/127.0.0.1 và database storagehub_schema_test_*. Test từ chối schema chung. Fixture mới: `storagehub_schema_test_develop_a3901bb380` và `storagehub_schema_test_develop_f2e9cadb4d`. Test từng case tạo DB riêng, không drop/clean. Test relocation nâng cấp DB test cũ phía trên, đối chiếu lịch sử cũ và kiểm migrate/replay. Suite 74 dùng cấu hình outOfOrder=true vốn có ở RentalIntegrationMySqlSchemaTests; đã đổi riêng fixture này thành false để chạy lại ca fresh full với ràng buộc chặt hơn. Runtime/profile và các test alignment/default/upgrade không bật out-of-order.

Chạy lại 6 test RentalIntegrationMySqlSchemaTests với **outOfOrder=false** trên DB mới `storagehub_schema_test_ordered_a47e520b85`: 6 PASS, 0 failure/error/skipped, BUILD SUCCESS lúc 09:48:45 (15.044 s). Đây là chạy lại 6 case trong 74 case phía trên, không phải tổng 80 case khác nhau.

BE startup **PASS với ddl-auto=validate** trên `storagehub_schema_test_develop_a3901bb380`. Dùng Java main từ source đã Maven compile cùng dependency classpath; không dùng spring-boot:run lifecycle testCompile đang bị chặn. Profile rental-support-schema chỉ kết nối đúng DB test đã khai báo; seed/admin/backfill/ledger/communication/mail/VNPay/auto-close OFF. Port riêng 8082; JWT tạm sinh trong process, không lưu/push credential.

- 09:47:25: Flyway Successfully validated 15 migrations; schema up to date, no migration necessary.
- 09:47:31: Initialized JPA EntityManagerFactory (ddl-auto=validate, không create/update).
- 09:47:44: Started StoragehubApplication in 29.285 seconds.
- GET /v3/api-docs = 200; GET /api/customer/rentals anonymous = 401.
- Process test PID 18568 đã dừng sau kiểm chứng; giữ dữ liệu test, không drop/clean.

Đây là schema/startup/anonymous API smoke test, **không phải browser E2E hoặc authenticated API đọc-ghi**. Không giao dịch VNPay hoặc gửi mail thật. Skill verification giúp kiểm tra datasource thật → migration → JPA validate → HTTP; không chứng nhận toàn bộ flow team.

Logs tại thư mục temp của phiên Windows: swp-migration-isolated-final-20261011.log, swp-migration-ordered-test-20261011.log, swp-migration-validate-startup-final-20261011.log. Full-testCompile blocker ở swp-develop-migration-final-test-20261011.log.

Diff-check phần migration/compile fix/tests/docs: PASS. Toàn bộ diff merge vẫn có trailing whitespace từ develop ở LocalDemoDataInitializer.java:72; không sửa teammate chỉ để làm check xanh.

## Git handoff

Merge source BE không có unmerged paths; giữ pending merge (HEAD 8fafb75, MERGE_HEAD b7ed639). Sửa migration được stage để SQL trùng không còn trong default location của index. Chưa commit/push, không sửa lịch sử remote. Script local giữ nguyên. Cần cập nhật hai test nhận kho theo contract develop mới trước khi chứng nhận full suite/flow và chốt merge.

## BLOCKED_ROLLOUT — DB chung

Chưa đọc lịch sử DB chung. Nếu version 0909/0910/1001 ở DB team có script khác chuỗi đã kiểm chứng, source mặc định hiện tại không tương thích lịch sử đó. Không chạy trên DB chung đến khi có SELECT version,description,type,script,checksum,success từ flyway_schema_history và đối chiếu schema.

Giữ nguyên lịch sử DB; chọn chuỗi resource tương thích riêng và kiểm thử bản sao trước rollout. Không áp dụng archive cùng default location vì sẽ tái tạo version trùng. Xem README archive về giới hạn tương thích.

Ngay cả khi kiểm thử PASS trên MySQL 8.4, không tự chứng nhận TiDB hoặc các flow team. Default application.properties từ develop dùng ddl-auto=update; lượt kiểm chứng này chỉ dùng override/profile ddl-auto=validate, không sửa shared default để né migration.
