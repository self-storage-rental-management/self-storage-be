# Rollout schema hồ sơ thuê và hỗ trợ

Ngày soạn: 11/10/2026, Asia/Saigon. Đây là hướng dẫn triển khai, không phải xác nhận DB chung đã được cập nhật.

Cập nhật: ledger 2026101001 đã vào db/migration theo phê duyệt, BE sẽ tự phát hiện ở location mặc định; không còn cần db/integration-migration. Callback riêng chặn engine chưa chứng nhận/bảng trùng trước DDL ledger. Mục này thay thế hướng dẫn opt-in ledger trước đó; Payment vẫn opt-in.

## Phạm vi

Migration SQL `2026101001` thêm 6 bảng rental_ledger_* và notification_outbox, không ALTER/DROP bảng chung. Migration Payment opt-in mới `2026101101` bổ sung gateway_intent_id theo Entity hiện hành; mã legacy chỉ là định danh chưa xác minh, không backfill tiền/bằng chứng thanh toán. Không seed/cấp quyền, không sửa checksum SQL đã áp dụng, ngày thuê, tiền hoặc writer. Xem [PAYMENT_GATEWAY_MIGRATION.md](PAYMENT_GATEWAY_MIGRATION.md).

DB local đã xác minh MySQL 8.4.11. Prod đang cấu hình TiDbMySqlDialect, nên không suy DB chung là MySQL chỉ từ README/JDBC prefix. Guard chủ động từ chối TiDB và phiên bản chưa kiểm chứng; không bỏ guard để ép chạy.

## Trước khi chạy trên DB chung

1. Xác định datasource thật, engine/version và tên DB; không gửi password/URL chứa credential vào Git/chat.
2. Sao lưu DB và kiểm tra khả năng phục hồi; MySQL DDL không bảo đảm rollback toàn bộ.
3. Kiểm thử trên bản sao DB chung, so sánh Flyway history/checksum, kiểu BINARY(16) ID và bảng trùng tên. Các test chỉ dùng DB mới rỗng không thay thế bước thử dữ liệu chung.
4. Kiểm tra ảnh hưởng FK RESTRICT tới xóa/lưu trữ Rental/User/Facility/File/Notification, chốt cửa sổ rollout với người phụ trách DB.
5. Có đủ các migration nền tảng; không baseline/clean/repair/reset để vượt lỗi. Giữ nghiệp vụ thiếu nguồn bị chặn.

## Chạy có kiểm soát

Triển khai BE mới đã build/test. Cấp bốn biến môi trường trên máy deployment, bằng secret/config của môi trường:

```text
STORAGEHUB_DB_URL=<JDBC URL đã xác minh, không thêm createDatabaseIfNotExist>
STORAGEHUB_DB_USERNAME=<tài khoản migration được cấp quyền cần thiết>
STORAGEHUB_DB_PASSWORD=<secret của môi trường>
STORAGEHUB_SCHEMA_ROLLOUT_DATABASE=<tên DB đích chính xác>
```

Giữ các biến JWT/CORS/mail của staging/deployment như hiện có, không lấy credential hoặc run-dev local runner đưa vào DB chung.

Sau backup và kiểm thử bản sao DB đích, bật profile rollout sau profile môi trường để các tùy chọn schema an toàn có hiệu lực. Ví dụ staging trên Windows:

```powershell
.\mvnw.cmd '-Dspring-boot.run.profiles=staging,rental-support-schema' spring-boot:run
```

Không chạy lệnh trên DB chưa kiểm chứng. Profile không đặt host/password mặc định, không bật ledger/communication/rental-period. Giữ thứ tự profile và không dùng env/SPRING_APPLICATION_JSON để ghi đè ddl-auto, Flyway locations, clean/baseline hoặc bật seed. Cần kiểm tra cấu hình hiệu lực ở deployment vì biến môi trường có thể ghi đè profile.

Trước DDL, strategy đọc connection trực tiếp, kiểm đúng database(), version()/product MySQL 8.4 và các bảng/history integration hiện có. TiDB hoặc DB sai tên bị dừng. Sau migrate, hai schema guard đọc các cột cần dùng; không tạo số dư hoặc thông báo giả. Cấu hình shared hiện có vẫn giữ nguyên, merge PR không tự chạy profile này.

## Kiểm tra sau rollout

```sql
SELECT VERSION(), DATABASE();
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC;
SELECT table_name FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN (
  'rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts',
  'rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox'
);
```

Yêu cầu: migration 2026101001 và 2026101101 success=1, đủ 7 bảng integration và gateway_intent_id VARCHAR(100)/NOT NULL/UNIQUE, Flyway validate và Hibernate validate PASS; đối chiếu FK/index/CHECK và dữ liệu chung trước/sau. Không bật module nếu financial coverage/policy/hold/lifecycle/delivery vẫn thiếu. Không kết luận PAID mô phỏng là đã thu tiền thật.

Khi bỏ profile rollout, giữ db/migration (đã chứa ledger); nếu đã áp dụng Payment thì giữ thêm db/paymentmigration cùng class Java tương ứng. Không cần location ledger cũ và không giữ SQL trùng. Giữ clean-disabled=true/ddl-auto=validate, không DROP bảng để rollback code. Default/staging/prod không tự thêm Payment.

Nếu rollout lỗi, dừng, ghi nhận bảng/history đã tạo và xử lý theo backup/rollout plan; không tự xóa hoặc repair migration. Nếu engine là TiDB, giữ BLOCKED_TARGET cho tới khi SQL được kiểm chứng trên bản sao TiDB và có kế hoạch tương thích riêng.

## Kiểm thử trong repo

`RentalSupportSchemaRolloutTests` kiểm việc từ chối DB/engine sai và cấu hình profile. `RentalIntegrationMySqlSchemaTests` opt-in chỉ nhận URL localhost với DB rỗng tên storagehub_schema_test_*, chạy migration thật qua strategy và kiểm schema/FK/outbox/readback/retry/idempotent rollout. Không nhận DB chung, không xóa DB test khi xong.

Kết quả lượt chuẩn bị này: xem báo cáo `docs/RENTAL_SUPPORT_SCHEMA_PREPARATION_20261011.md` trong repo FE. Chưa có chứng nhận trên bản sao dữ liệu chung, TiDB hoặc toàn bộ flow team.
