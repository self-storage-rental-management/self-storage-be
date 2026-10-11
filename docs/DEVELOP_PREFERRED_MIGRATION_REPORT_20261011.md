# Migration ưu tiên develop, giữ tương thích DB cũ — 11/10/2026

## Kết quả và phạm vi

Đã thực hiện phương án được duyệt: dùng version develop cho DB mới, chuyển ledger sang version mới, resolve nguyên bản SQL đã áp dụng và không chạy thêm VNPay `0910`. Chỉ kiểm thử trên MySQL **8.4.11**, localhost:3306, database `storagehub_schema_test_*`. **Chưa áp dụng DB chung/TiDB; chưa commit/push hoặc kết thúc merge.** FE không sửa/merge.

BE ở `feature/be-duong`, HEAD `8fafb75`, MERGE_HEAD `b7ed639`; backup `backup/be-duong-before-migration-20261011-093607`. Các thay đổi nghiệp vụ/security/Entity nhập từ develop không phải sửa mới của lượt xử lý migration. Hai sửa khai báo/import trong CustomerReservationService từ lượt trước được giữ: bỏ field checkInRepository trùng, thêm import FileAssetStatus; không thay method/constructor để né test cũ.

## Chuỗi và bằng chứng SQL bất biến

| Version | DB mới | DB đã chạy chuỗi cũ | Checksum thực tế |
| --- | --- | --- | --- |
| 2026100909 | add_vnpay_payment_fields, develop-0909 | remove_legacy_rental_end_date, legacy-0909 | develop -1270745881; legacy -1449372527 |
| 2026100910 | Không resolve/chạy | Chỉ resolve nếu SQL VNPay này đã success | Giữ checksum gốc, Flyway validate kiểm tra |
| 2026101001 | add_customer_checkin_appointment, develop-1001 | create_rental_ledger_and_notification_outbox, legacy-1001 | develop -84044293; legacy -1198625055 |
| 2026101101 | Payment opt-in, db/paymentmigration | Giữ class/version đã áp dụng | NULL theo Java migration cũ, không tự gán checksum mới |
| 2026101102 | Xác minh/bổ sung cột VNPay và appointment thiếu | Cùng Java migration bất biến từ lượt trước | 2026101102 |
| 2026101103 | Tạo 7 bảng ledger/outbox | Kiểm chứng và tái sử dụng 7 bảng ledger, không tạo lại | 2026101103 |

DB mới common có **15 migration**; common + Payment opt-in có **16**. Fixture có VNPay 0910 đã applied có thêm một history row hợp lệ. Không dùng repair, clean, baseline, ignore validation hoặc out-of-order. DB mới ưu tiên develop không chạy lại SQL remove end_date của nhánh cũ; không tuyên bố mọi cột dư trong hai schema giống nhau, và không tái tạo cột đã bị migration cũ bỏ.

`git hash-object` từng SQL ở history folder khớp blob gốc HEAD/origin/develop:

| SQL | Git blob |
| --- | --- |
| legacy 0909 | 0f73c14ce08c190a02a18cc7a0b429e41e02e508 |
| legacy ledger 1001 | 4a732b5e4c5c1ad6801892428c9bdd23e8b0b94a |
| develop VNPay 0909 / 0910 | c27920fd450ee3f01875214e365a0cb9cc977f4c |
| develop appointment 1001 | d67325fb168b3de2e5707ffa8b7d2e4c0df9053f |

Folder relocation không đổi script/content/checksum đã applied. Java `1102` không sửa so với bản đã kiểm thử/applied trước đó. Không sửa Flyway history trong DB.

## File và cơ chế bảo vệ

- `DevelopMigrationHistoryLocations.java`: Spring Boot Flyway customizer, chỉ SELECT datasource thực tế và history. Chọn đúng một 0909 và một 1001; giữ Payment opt-in; 0910 chỉ resolve khi đã applied đúng script/type/success. Script lạ, history fail hoặc version trùng bị chặn. Flyway vẫn xác minh checksum. Không thêm history root vào location; standalone Flyway dùng helper `configure(...)` trước `load()`.
- `db/migration-history/{develop-0909,develop-1001,legacy-0909,legacy-1001,applied-vnpay-0910}/*.sql`: các SQL gốc bất biến. Archive trước đó không còn chứa bản SQL trùng; README chỉ trỏ đến cơ chế hiện tại.
- `V2026101103__ensure_rental_ledger_and_notification_outbox.java`: mới, MySQL 8.4 gate. SQL ledger gốc có SHA256 chuẩn hóa CRLF `0e7ad29986d1cfb6e5e5dd3380cc464067ef16e83a13a52b3d0eaf370426766f`. Khi chưa có bảng integration mới tạo schema; nếu có bảng phải đủ 7, có history legacy 1001 checksum -1198625055 và fingerprint SHOW CREATE TABLE khớp `f933f9c259c8362e7795926dfea0edfc325c4396a965c46bb2530bcf995104af`.
- Fingerprint bao gồm kiểu/null/default/generated binary bridges, FK, index, CHECK và engine/collation. Khác schema/collation chưa chứng nhận thì dừng, không sửa bảng cũ. MySQL DDL có implicit commit: lỗi/kết nối gián đoạn giữa các CREATE có thể để lại bảng một phần; phải dừng và xử lý rollout, không tự retry bằng IF NOT EXISTS hoặc repair/reset.
- `LedgerMigrationSafetyCallback.java`: chỉ gate legacy 1001 ledger, không nhầm appointment 1001.
- `RentalSupportSchemaRolloutConfiguration.java`: nhận ledger mới 1103 hoặc lịch sử ledger cũ đúng script; giữ verify target, validate và schema guards.
- Test migration/alignment/default/relocation/schema và hai test mới được cập nhật. Không sửa test/business writer của teammate để làm full suite xanh.
- Changelog, API contract và hướng dẫn rollout cập nhật; báo cáo reconciliation trước được đánh dấu lịch sử/superseded.

Hai resource cũ trong `target/classes/db/migration` được xóa sau khi kiểm chứng đúng đường dẫn build và nội dung khớp nguồn đã chuyển; đây là build artifact có thể tái tạo, không phải source hoặc dữ liệu DB. Các DB test được giữ, không drop/clean. Script local và credentials không stage.

## Kiểm thử

| Kiểm tra | Trạng thái | Bằng chứng |
| --- | --- | --- |
| Main compile | PASS | mvnw.cmd -Dmaven.test.skip=true compile, 424 source files, BUILD SUCCESS 09:58:37 |
| Suite migration chọn riêng | PASS | 81 tests, 0 failures/errors/skipped, BUILD SUCCESS 10:07:01, 1m15s |
| Fresh develop common/full | PASS | 15/16 unique migration; 0909 VNPay, 1001 appointment; 1103 ledger; không có 0910 mới |
| Upgrade actual legacy DB | PASS | storagehub_schema_test_payment_full_b45468d5, validate/migrate/replay; lịch sử cũ giữ nguyên, 1103 thêm một lần |
| Develop applied + legacy VNPay0910 fixtures | PASS | history cũ giữ nguyên; 0910 đã applied chỉ resolve/validate, không execute lần hai |
| Drift/orphan/partial/schema mismatch | PASS | Negative tests yêu cầu chặn; không sửa schema lệch/hợp thức hóa bảng không có history |
| Ledger/outbox persistence/FK/retry | PASS | Các case JDBC/H2 và MySQL schema/readback thuộc suite 81; không phải thanh toán thật |
| BE startup ddl-auto=validate, fresh + legacy | PASS | Hai DB test đều Flyway validated 16, JPA initialized, BE Started; docs 200 và rentals anonymous 401 |
| Full Maven test-compile | BLOCKED | 7 compile errors trong 2 test Customer có constructor/confirmReceipt cũ; không có full-suite PASS |
| DB chung/TiDB, browser E2E, real VNPay | NOT VERIFIED | Không chạy hoặc bật trong task migration này |

Suite gồm: DevelopMigrationHistoryLocationsTests (3), LedgerMigrationSafetyCallbackTests (2), RentalSupportSchemaRolloutTests (7), NotificationOutboxJdbcTests (14), DevelopMigrationAlignmentMySqlTests (6), DevelopPreferredHistoryMySqlTests (4), LedgerDefaultMigrationMySqlTests (3), LedgerMigrationRelocationMySqlTests (1), LedgerStoreJdbcTests (27), PaymentGatewayMigrationMySqlTests (8), RentalIntegrationMySqlSchemaTests (6).

Full `mvnw.cmd test-compile` kiểm lại sau thay đổi vẫn FAIL: `CustomerReservationServiceTests.java:71` và `CustomerReceiptPeriodCompatibilityTests.java:27` constructor cũ; test compatibility còn gọi method confirmReceipt đã được develop thay đổi. Owner flow Customer/Reservation cần đồng bộ fixture/assertion theo contract hiện tại; không khôi phục method cũ hoặc xóa assertion trong task này.

Vì testCompile toàn repo bị chặn, suite migration được compile riêng bằng javac --release 21 -encoding UTF-8 với dependency classpath + target/classes, rồi chạy cùng 11 class bằng `mvnw.cmd -Dtest=<11 classes above> surefire:test`. Đây là suite chọn riêng, không phải lifecycle/full test PASS. Datasource test phải localhost và tên storagehub_schema_test_*. `STORAGEHUB_SCHEMA_TEST_URL`, USERNAME/PASSWORD và `STORAGEHUB_SCHEMA_RELOCATION_TEST_URL` chỉ ở process, không lưu credential vào repo.

Lượt đầu PowerShell dừng bởi cảnh báo Mockito trên stderr; sau đó lượt có URL cổng 3307 FAIL kết nối (76 executed/23 errors), không phải kết quả migration. Đã xác minh docker port là 3306 và chạy lại suite đúng target để có 81 PASS trên đây; không sửa config ứng dụng để né lỗi. Log cuối: `%TEMP%/swp-develop-preferred-tests-20261011.log`. Log compile chung: `%TEMP%/swp-develop-preferred-full-testcompile-20261011.log`.

## Startup / HTTP smoke và giới hạn

Story kiểm chứng theo skill verification: datasource test → chọn history resource → Flyway → JPA schema validate → HTTP response. Không chỉnh FE/browser/API contract; không chứng nhận response auth đọc/ghi hoặc toàn bộ D1–D5 E2E.

Chạy Java main từ Maven-compiled classes + dependency classpath để tránh lifecycle testCompile đang BLOCKED; profile rental-support-schema, ddl-auto=validate, port riêng 8082. Seed/admin/backfill/ledger/communication/mail/VNPay/auto-close OFF; hai job reservation/payment complaint có initial delay 1 giờ và server test dừng trước đó. JWT ngẫu nhiên trong process, không lưu/push.

- DB mới `storagehub_schema_test_preferred_57abb30a57`: Flyway validated 16 migration 10:07:35; JPA initialized 10:07:45; BE Started 10:08:11. GET tài liệu ban đầu timeout 10s, retry 200; GET /api/customer/rentals anonymous 401. PID test 8964 đã dừng, không dừng server khác.
- DB legacy `storagehub_schema_test_payment_full_b45468d5`: Flyway validated 16 migration 10:09:26; JPA initialized 10:09:35; BE Started 10:09:56. GET /v3/api-docs 200; GET /api/customer/rentals anonymous 401. PID test 23172 đã dừng, không dừng server khác. Log ở `%TEMP%/swp-preferred-{fresh,legacy}-startup-20261011.{out,err}.log`.

Hai DB SELECT-confirm đủ 7 bảng; legacy 0909/1001 checksums giữ -1449372527/-1198625055, new develop -1270745881/-84044293; 1103 checksum2026101103 success1. Không có 0910 ở hai target này. Các giá trị financial/refund/appointment hiện có được kiểm trong alignment replay; migration không xác nhận khoản thu/hoàn thật hoặc financial coverage.

## Handoff

Merge còn pending, không có unmerged path. Chưa auto-commit/push. Các file migration/config/test/docs liên quan được stage để giữ index merge nhất quán; không stage start-mysql.bat hoặc run-dev scripts local. Scope diff-check PASS; global diff-check có trailing whitespace từ develop trong LocalDemoDataInitializer.java:72, không sửa teammate chỉ để làm check xanh. HEAD/MERGE_HEAD giữ 8fafb75/b7ed639; FE không thay đổi ngoài run-dev.bat untracked vốn có.

DB shared/TiDB vẫn BLOCKED_TARGET cho tới khi xác minh engine/schema/history trên bản sao đúng target. Không bỏ MySQL gate, không reset history, không dùng ddl-auto=create/update. Giữ các nguồn financial/policy/hold/lifecycle chưa đủ ở UNKNOWN/BLOCKED; bảng ledger mới không tự tạo nguồn dữ liệu tài chính đầy đủ hoặc mở quyền/module.
