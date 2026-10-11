# Ledger/outbox đã chuyển vào Flyway chung

Cập nhật 11/10/2026: V2026101001__create_rental_ledger_and_notification_outbox.sql hiện nằm ở src/main/resources/db/migration, theo phê duyệt đưa 7 bảng vào cơ chế chung. Thư mục integration-migration chỉ giữ README, không còn SQL này; không cần location riêng cho ledger. Không chép SQL vào cả hai locations vì sẽ trùng version.

Version, nội dung SQL, checksum Flyway -1198625055 và SHA256 0E7AD29986D1CFB6E5E5DD3380CC464067EF16E83A13A52B3D0EAF370426766F không đổi. Comment opt-in trong SQL là lịch sử, giữ nguyên để không sửa migration đã áp dụng. DB đã áp dụng chỉ validate/replay-noop, không repair/baseline/reset.

BE dùng location mặc định classpath:db/migration sẽ phát hiện ledger khi khởi động. LedgerMigrationSafetyCallback chỉ kiểm tra trước SQL version này: MySQL 8.4 đã chứng nhận và không có bảng trùng thiếu history. Engine/version khác, gồm TiDB chưa kiểm chứng, bị chặn trước DDL ledger. Các migration nền pending có thể đã chạy trước callback; deployment vẫn cần backup/history/bản sao.

Migration thêm đúng 6 bảng rental_ledger_* và notification_outbox; không ALTER/DROP/seed tiền/cấp quyền. Ledger/communication vẫn mặc định tắt; schema không chứng minh đầy đủ coverage/policy/hold/lifecycle/delivery. Không trực tiếp chạy DB chung trong lượt thay đổi source.

Payment 2026101101 vẫn opt-in ở classpath:db/paymentmigration. Profile rental-support-schema hiện dùng db/migration + db/paymentmigration; default/staging/prod không tự thêm Payment. Sau rollout Payment giữ location/class Java tương ứng. Location ledger cũ có thể bỏ.

Tests: LedgerDefaultMigrationMySqlTests, LedgerMigrationRelocationMySqlTests, LedgerMigrationSafetyCallbackTests, RentalIntegrationMySqlSchemaTests. Chỉ DB test localhost/storagehub_schema_test_*, không clean/drop. Hướng dẫn: docs/RENTAL_SUPPORT_SCHEMA_ROLLOUT.md; báo cáo FE: docs/LEDGER_COMMON_MIGRATION_REPORT_20261011.md.
