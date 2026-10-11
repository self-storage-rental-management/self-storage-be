# Migration Payment gateway intent

Ngày kiểm chứng: 11/10/2026. Chỉ áp dụng trên DB test; chưa rollout DB chung.

## Phạm vi và location

Java migration `2026101101`: `src/main/java/db/paymentmigration/V2026101101__add_payment_gateway_intent_id.java`. Location opt-in: `classpath:db/paymentmigration`. Sau khi ledger chuyển vào Flyway chung, profile `rental-support-schema` dùng db/migration + db/paymentmigration. Defaults/staging/prod không tự bật Payment; writer/Entity Payment không đổi. Không chạy `seed-mysql.bat`.

Không sửa migration SQL đã áp dụng. Java migration có checksum mặc định null của Flyway; không tuyên bố có SQL checksum. Giữ version/source đã triển khai bất biến, thay đổi sau áp dụng phải dùng version mới. Sau rollout Payment giữ db/migration + db/paymentmigration và class Java; location ledger cũ không còn cần thiết.

## Dữ liệu cũ

1. Preflight MySQL 8.4, payments.id BINARY(16), kiểu/độ dài/default/generated metadata, charset/collation, collision giá trị sau chuẩn hóa và tên unique index.
2. Chưa có cột: ADD VARCHAR(100) nullable trước. Cột đúng chuẩn đã có: giữ charset/collation và mã hiện hữu, kể cả SIMULATED; không tạo thêm unique index tương đương.
3. Chỉ NULL/chuỗi trống sau TRIM nhận `LEGACY-UNVERIFIED-<hex Payment ID chữ thường>`. Đây là định danh chưa xác minh, không phải gateway transaction, biên lai, bằng chứng thu tiền hoặc xác nhận simulation.
4. Enforce NOT NULL/full-column UNIQUE. Không thay amount/currency/status/paidAt/processedAt/idempotency/version/updatedAt. RentalBookingEvidenceReader hiện hành phân loại legacy là UNVERIFIED, không nâng thành tiền thật hoặc financial coverage.
5. Mã trùng, type/length không phù hợp, default/generated lạ hoặc tên index xung đột: dừng trước ALTER/UPDATE để đối chiếu; không cắt reference hoặc tự ghi đè mã đã có.

DDL MySQL có thể auto-commit. Backup, thử phục hồi và kiểm thử bản sao dữ liệu chung trước rollout. Không clean/repair/reset/đổi ddl-auto để vượt lỗi. Những schema khác MySQL 8.4 giữ chặn cho đến khi được kiểm chứng riêng. Không dùng marker legacy làm dữ liệu đối soát đã xác minh.

## Kiểm thử

- `PaymentGatewayMigrationMySqlTests`: 8 schema fixture riêng cho missing column/legacy, nullable/blank, schema Hibernate hiện có, charset/collation, duplicate, length mismatch, index collision và generated-legacy collision. Fixture Payment tối giản chỉ để kiểm migration. Baseline=0 chỉ dành cho fixture tự tạo, không phải hướng dẫn baseline DB chung. Không xóa schema test khi xong.
- `RentalIntegrationMySqlSchemaTests`: 6 ca chạy toàn bộ Flyway trên DB test rỗng, gồm migration Payment và schema ledger/outbox/replay/FK/readback.
- `RentalSupportSchemaRolloutTests`, `SimulatedPaymentServiceTests`, `RentalBookingEvidenceJpaTests`: cấu hình, writer hiện hữu và nghĩa của mã legacy.

Kết quả startup/HTTP và test cụ thể: báo cáo FE `docs/PAYMENT_GATEWAY_MIGRATION_REPORT_20261011.md`. Migration PASS không chứng minh full E2E hoặc dữ liệu tài chính đã đầy đủ.
