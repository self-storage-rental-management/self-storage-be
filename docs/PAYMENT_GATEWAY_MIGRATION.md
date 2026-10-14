# Migration Payment gateway intent

## Trùng phiên bản sau merge develop - 14/10/2026

Develop dùng 2026101301 cho cleanup gói thuê và 2026101302 cho thông tin cơ sở; hai version này trước đây đã được renewal opt-in dùng cho calendar và gateway. `DevelopMigrationHistoryLocations` đọc lịch sử thật để chọn đúng script/class đã áp dụng, giữ nguyên nội dung, checksum và identity lịch sử. Không chạy repair, reset hoặc sửa flyway_schema_history.

- Database mới hoặc đã chạy develop: resolve SQL develop 1301/1302; gateway mới dùng Java migration 2026101404.
- Database đã chạy calendar/gateway cũ: resolve lại đúng SQL calendar 1301 và class JDBC gateway 1302. Bridge 1401/1402 áp dụng phần develop bị trùng; các migration develop thấp hơn chưa áp dụng được chạy out-of-order trong trường hợp lịch sử legacy đã xác định.
- Calendar mới dùng location `classpath:db/renewalcalendarmigration`, version 2026101403. Location cũ `db/renewal-calendar-migration` được customizer chuyển sang location mới. Calendar đã có history 1301 không tạo lại bảng; bảng có sẵn nhưng thiếu history hợp lệ sẽ dừng.
- Runner giữ `classpath:db/sandboxpaymentmigration` như tùy chọn bật gateway; customizer loại bỏ việc scan class 1302 trùng và đăng ký class phù hợp một cách tường minh.
- Migration không rõ nguồn hoặc failed phải dừng để đối chiếu. Sau thay đổi location, cần build sạch để loại bỏ resource cũ còn trong target/classes. Không tự áp dụng trên DB chung trong lượt sửa này.

## Gateway Ledger opt-in version 1302 — 13/10/2026

`db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence` tại `classpath:db/sandboxpaymentmigration` dùng version mới vì một số DB local đã có `1202` với script Renewal resource binding cũ. Không thêm đồng thời location `db/ledger-sandbox-migration` chứa SQL cùng version `1202` trên các DB này.

Migration mới kiểm tra MySQL 8.4, dữ liệu receipt hiện có và schema gateway. Nếu gateway đã tồn tại và hợp lệ, chỉ xác minh. Nếu thiếu, chạy nội dung SQL gateway `1202` bất biến để cập nhật checks receipt và tạo bảng/FK/unique evidence. Checksum CRC32 lấy từ nội dung resource chuẩn hóa LF. Không sửa bản ghi Flyway hay dữ liệu thanh toán.

Nếu Flyway history đã ghi nhận thành công SQL `V2026101202__sandbox_gateway_ledger_evidence.sql`, history router tự thêm `classpath:db/ledger-sandbox-migration` để Flyway xác minh bản gốc và checksum. Database mới hoặc database có script `1202` khác không được tự thêm location này. Lỗi `Detected applied migration not resolved locally: 2026101202` của lịch sử gateway được xử lý bằng cách khôi phục đường dẫn resource, không dùng repair hoặc bỏ validation.

Với lịch sử `V2026101202__add_renewal_vnpay_resource_binding.sql`, router dùng bản SQL nguyên gốc tại `db/migration-history/legacy-binding-1202`, checksum `2025292548`, và không chạy lại cùng nội dung dưới version `1203`. Database mới hoặc đã áp dụng `1203` dùng `db/migration-history/develop-1203`. Cần `mvn clean compile` sau khi đổi các location để xóa resource cũ trong `target/classes`; không sửa history database.

Nếu history đã có cả `1202` và `1203` với hai tên resource-binding này, router cung cấp cả hai resource nguyên gốc cho Flyway validate checksum. Hai version đã áp dụng không chạy lại; không xóa hoặc sửa các bản ghi lịch sử.

Runner local `-EnableRenewalPayments` thêm location mới và profile `shared-payment-schema`, xác minh target `storagehub_local` trước migrate. Không kết hợp profile này với các profile schema rollout khác. Backup trước chạy; DDL MySQL auto-commit nên bản ghi failed/schema dở dang phải được đối chiếu, không tự repair/reset hoặc baseline.

Ngày kiểm chứng: 11/10/2026. Chỉ áp dụng trên DB test; chưa rollout DB chung.

Chuỗi hiện tại ưu tiên develop `0909` VNPay, `1001` lịch nhận kho; ledger mới là `1103`. Bộ SQL lịch sử được tự chọn bằng customizer trên datasource thật. `0910` chỉ resolve khi đã áp dụng, không chạy thêm trên DB mới. Giữ `1101` Payment opt-in và `1102` bất biến; không sửa dữ liệu/lịch sử đã áp dụng. Xem [hướng dẫn rollout schema](RENTAL_SUPPORT_SCHEMA_ROLLOUT.md).

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
# Renewal reconciliation migration — 12/10/2026

`V2026101204__renewal_payment_reconciliation.sql` adds durable work metadata and binding constraints to the shared Payment table. See [rollout and authority boundaries](RENEWAL_API_CONTRACT.md#current-payment-and-lifecycle). This migration preserves monetary history and accepted quotes and must follow 2026101203. Isolated Flyway/H2 migrate/replay was tested; MySQL rollout requires a separately configured test schema. No shared database migration was executed.
