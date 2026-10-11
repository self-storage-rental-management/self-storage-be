# Database changelog

## 2026-10-11 — Ưu tiên version develop, tương thích lịch sử đã áp dụng

Mục này thay thế phương án reconcile bên dưới. DB mới dùng `2026100909=add_vnpay_payment_fields` và `2026101001=add_customer_checkin_appointment` của develop. Ledger/outbox dùng Java version mới `2026101103`; `2026101102` giữ nguyên để bổ sung cột thiếu cho DB cũ.

SQL đã áp dụng được chuyển nguyên nội dung sang từng thư mục `db/migration-history/*`. `DevelopMigrationHistoryLocations` chỉ SELECT lịch sử trên datasource thực tế rồi chọn đúng biến thể; không sửa script/checksum/success trong DB. VNPay `0910` chỉ được resolve nếu đã áp dụng, không chạy mới để tránh thêm cột hai lần. Không scan cả thư mục history gốc.

`1103` tạo đủ 7 bảng khi chưa có bảng integration; chỉ tái sử dụng ledger cũ có đúng bằng chứng `1001` và fingerprint schema MySQL 8.4 đã kiểm chứng. Bảng thiếu, cấu trúc lệch hoặc không có lịch sử phù hợp bị chặn, không tự repair/reset. Không backfill tiền, cấp quyền hoặc bật module. **Chỉ kiểm thử DB test, chưa rollout DB chung.**

Chi tiết chuỗi, file và bằng chứng: [DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md](DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md).

## 2026-10-11 — Reconcile migration trùng sau merge develop (DB test)

Giữ nguyên migration đã kiểm chứng `2026100909=remove legacy rental end date`, `2026101001=create rental ledger and notification outbox`, cùng version/script/checksum. Ba SQL develop trùng được lưu nguyên nội dung ngoài default location tại `db/migration-archive/develop`, không tự thực thi.

Thêm Java `db.migration.V2026101102__align_vnpay_and_customer_checkin_schema` để bổ sung cột VNPay và lịch nhận kho; preflight toàn bộ cột, chỉ thêm cột thiếu, không sửa giá trị hiện có/history hoặc tự đổi kiểu. Cột sai cấu trúc dừng; engine ngoài MySQL 8.4 chưa được chứng nhận.

Đây là chuỗi tương thích với lịch sử DB test đã quan sát, **chưa rollout DB chung**. DB có version 0909/0910/1001 mang script khác phải đối chiếu và chuẩn bị resource tương thích riêng; không repair/delete history/baseline/ignore validation.

Chi tiết, baseline compile và test: [DEVELOP_MIGRATION_RECONCILIATION_20261011.md](DEVELOP_MIGRATION_RECONCILIATION_20261011.md).

Tài liệu này ghi các thay đổi schema trong môi trường local/demo. Local dùng Hibernate `ddl-auto=update` kết hợp Flyway cho các migration tương thích; staging/production dùng Flyway và Hibernate `ddl-auto=validate`:

```properties
spring.jpa.hibernate.ddl-auto=update
```

Các thay đổi schema cần được ghi nhận bằng migration SQL có version và kiểm thử trên bản sao dữ liệu.

## 2026-09-30 — Chuẩn hóa trạng thái Reservation và Payment

- Đổi trạng thái Reservation sang chữ hoa thống nhất FE–BE.
- Bỏ `DEPOSIT_PAID`; dùng `CONFIRMED` sau khi payment hợp lệ.
- Thêm `AWAITING_CUSTOMER_RECEIPT` và `REJECTED`.
- Chuẩn hóa Payment status và đổi purpose từ `DEPOSIT` thành `RESERVATION_DEPOSIT`.
- Đổi tên cột vật lý `system_settings.value` thành `setting_value` để tránh xung đột từ khóa H2.
- Nếu database cũ đã có reservation/payment, cần cập nhật giá trị enum cũ trước khi chạy phiên bản mới.

## 2026-09-30 — Mở rộng Reservation và khai báo hàng hóa

### Bảng `reservations`

Thêm các cột:

- `reservation_code`: mã đơn duy nhất.
- `goods_review_status`: trạng thái duyệt tổng của khai báo hàng hóa.
- `compatibility_result`: kết quả kiểm tra sức chứa và tải trọng.
- `goods_condition`: tình trạng đóng gói dùng chung cho lô hàng.
- `total_goods_volume_m3`, `total_goods_weight_kg`: tổng do BE tính.
- `hold_expires_at`, `payment_expires_at`.
- `confirmed_at`, `deposit_paid_at`.
- `cancelled_at`, `expired_at`, `rejected_at`.
- `cancel_reason`, `rejection_reason`.
- `version`: optimistic locking.

Chưa thêm index tối ưu hóa riêng vì dữ liệu local/demo có quy mô nhỏ. Vẫn giữ khóa chính, khóa ngoại và ràng buộc duy nhất của `reservation_code`. Index nghiệp vụ chỉ bổ sung khi có truy vấn thực tế và bằng chứng hiệu năng cần tối ưu.

### Bảng mới `reservation_goods_items`

Mỗi bản ghi là một loại kiện hàng thuộc Reservation, gồm:

- Danh mục hàng, tên/vật liệu tùy chỉnh khi là `OTHER`.
- Mô tả và ghi chú tùy chọn.
- Số lượng, dài/rộng/cao và khối lượng mỗi kiện.
- Cờ dễ vỡ và yêu cầu Staff duyệt.
- Trạng thái và ghi chú duyệt.

Tổng thể tích và tổng khối lượng không nhận trực tiếp từ FE. BE sẽ tính từ các dòng hàng hóa và lưu tổng trên Reservation.

### Ảnh hàng hóa

Không tạo bảng `reservation_goods_item_files`. Tái sử dụng bảng `file_assets` hiện có:

```text
entity_type = RESERVATION_GOODS_ITEM
entity_id   = reservation_goods_items.id
```

Khi upload, BE kiểm tra dòng hàng tồn tại, thuộc Reservation của Customer hiện tại và file là ảnh. Thời điểm tạo file đã có sẵn từ `BaseEntity`, không thêm thuộc tính “thời điểm liên kết” riêng.

### Ảnh hưởng tới thành viên khác

- FE phải dùng đúng mã enum trong `GoodsCategory` và `GoodsReviewStatus`.
- Staff review chỉ xử lý item có `requires_staff_review = true`.
- Manager/Staff không sửa trực tiếp tổng thể tích hoặc tổng khối lượng.
- Nếu Hibernate không thể bổ sung cột `reservation_code NOT NULL` do database local đã có dữ liệu cũ, cần sao lưu dữ liệu cần thiết rồi tạo lại database local hoặc điền mã cho các dòng cũ trước.

## 2026-10-01 — Xác minh email của Reservation

Tạo bảng `reservation_email_verifications`, mỗi Reservation có tối đa một bản ghi xác minh:

- `reservation_id`: khóa ngoại và duy nhất.
- `otp_hash`: chỉ lưu mã OTP đã băm, không lưu mã gốc.
- `expires_at`: thời điểm OTP hết hạn.
- `last_sent_at`: thời điểm gửi OTP gần nhất, dùng để giới hạn gửi lại.
- `verified_at`: có giá trị khi xác minh thành công.
- `failed_attempts`: số lần nhập sai của OTP hiện tại.
- `resend_count`: số lần gửi lại.

Không tạo riêng `locked_at`, `disabled_at`, `last_failed_at`, `consumed_at` hoặc `last_resend_at`. `created_at` và `updated_at` đã được kế thừa từ `BaseEntity`; trạng thái khóa có thể xác định bằng `failed_attempts` trong service.

## 2026-10-01 — Chụp giá tại thời điểm đặt kho

Tạo bảng `reservation_pricing_snapshots`, mỗi Reservation có một bản chụp giá:

- Mã gói giá và phiên bản chính sách được BO áp dụng.
- Số tháng thuê và đơn giá tháng.
- Tổng tiền trước giảm.
- Tỷ lệ và số tiền giảm của gói thuê.
- Tổng tiền thuê sau giảm.
- Số tiền cọc giữ chỗ thực tế.
- Thời điểm báo giá và hết hạn báo giá.

Không lưu mã giảm giá, loại giảm giá, lý do giảm, phụ phí hoặc currency vì không có trong luồng booking hiện tại và hệ thống chỉ dùng VND. Tỷ lệ cọc không lưu trong snapshot vì chính sách của bài cố định 40%; snapshot chỉ lưu số tiền cọc đã tính để giá cũ không thay đổi.

## 2026-10-01 — Duyệt hàng OTHER không dùng bảng lịch sử riêng

Không tạo bảng `reservation_goods_reviews` vì mỗi Reservation trong phạm vi đồ án chỉ cần một kết quả duyệt hàng hiện tại. Bổ sung trực tiếp vào `reservations`:

- `goods_review_status`.
- `goods_reviewed_by`: Staff thực hiện duyệt.
- `goods_review_submitted_at`: thời điểm gửi yêu cầu duyệt.
- `goods_review_due_at`: hạn xử lý hiển thị trên FE.
- `goods_reviewed_at`: thời điểm approve hoặc reject.
- `goods_review_note`: ghi chú/lý do của Staff.

Nếu sau này cần lưu nhiều vòng duyệt hoặc audit chi tiết, mới tách thành bảng lịch sử riêng. Hiện tại ActivityLog chung vẫn ghi nhận thao tác thay đổi quan trọng.

## 2026-10-01 — Payment liên kết trực tiếp Reservation

- Thay `Payment.reservationId` dạng UUID rời bằng quan hệ JPA `ManyToOne` tới `Reservation`.
- Cột `payments.reservation_id` trở thành khóa ngoại bắt buộc trong model.
- Thêm trường `version` bằng `@Version` để Hibernate phát hiện hai tiến trình cùng cập nhật một payment.
- Payment service phải tìm Reservation tồn tại trước khi tạo payment mô phỏng.

Nếu database local đã có payment không có `reservation_id` hợp lệ, cần xóa dữ liệu payment demo cũ hoặc gắn lại đúng Reservation trước khi chạy backend.

## 2026-10-01 — Hoàn tất các bảng nền tảng booking

### `rental_package_policies`

Lưu gói thuê do BO cấu hình theo cơ sở: mã/tên gói, số tháng, tỷ lệ giảm, phiên bản chính sách, trạng thái và thời gian hiệu lực. Unique `(facility_id, code)` là ràng buộc nghiệp vụ, không phải index tối ưu hóa bổ sung.

### `payment_webhook_events` — đã loại bỏ ngày 2026-10-02

Bảng này từng được tạo cho contract cổng thanh toán cũ. Model/repository/API webhook đã bị xóa khi chuyển hoàn toàn sang payment mô phỏng nội bộ. Với database local đang dùng `ddl-auto=update`, Hibernate không tự drop bảng cũ; có thể xóa thủ công sau khi xác nhận không cần dữ liệu demo.

### `booking_documents`

Lưu một `BOOKING_CONFIRMATION` cho mỗi Reservation, liên kết tới `FileAsset`. Đây là chứng từ xác nhận booking sau thanh toán, không phải hợp đồng thuê đã ký.

Ngày 2026-10-02 đã bổ sung service/API phát hành idempotent. File PDF được lưu với `file_assets.entity_type=BOOKING_DOCUMENT`, `entity_id=reservation_id`, MIME `application/pdf` và checksum SHA-256. Không tạo `SIGNED_CONTRACT` trong booking module.

### Thay đổi bảng hiện có

- `facilities`: bỏ timezone riêng; toàn hệ thống dùng `Asia/Ho_Chi_Minh`.
- `unit_types`: thêm code, số khung và ba kích thước khung; bỏ `price_per_m3`; diện tích/thể tích do Java tính từ kích thước; thêm `image_url` nullable và version.
- `storage_units`: thêm `available_from`, `last_released_at` và version.
- `rentals`: dùng `contract_end_date`, thêm `actual_returned_at` và `completed_at` để không nhầm ngày dự kiến với ngày thực trả.
- `payments`: thêm provider request/transaction reference, paid/failure/refund fields và version.

Các cột cũ như `timezone`, `price_per_m3`, `area_m2`, `volume_m3` hoặc `rentals.end_date` có thể vẫn còn trong MySQL khi dùng `ddl-auto=update`, nhưng code không còn sử dụng. Nếu dựng database demo sạch, Hibernate chỉ tạo schema theo entity mới.

## 2026-10-02 — Payment mô phỏng và payment grace

- Thêm `PAYMENT_GRACE`, `PAYMENT_REVIEW` vào `ReservationStatus`.
- Thêm `NOT_RECEIVED` vào `PaymentStatus`.
- Thêm `reservations.complaint_expires_at` để giữ capacity thêm 30 phút sau payment deadline.
- Thêm `reservations.archived_at` để ẩn đơn kết thúc mà không hard-delete dữ liệu đối soát.
- Thêm cấu hình local/demo `app.payment.simulation-outcome=SUCCESS|FAILED|NOT_RECEIVED`.
- Endpoint Customer không nhận amount hoặc outcome; amount luôn lấy từ `ReservationPricingSnapshot`.

`AWAITING_PAYMENT` đến hạn chuyển sang `PAYMENT_GRACE` thay vì `EXPIRED`. Trong grace, Reservation vẫn trừ capacity. Hết `complaint_expires_at` mà chưa có complaint thì mới chuyển `EXPIRED`, đặt `archived_at` và giải phóng capacity.

Các cột/bảng thanh toán cũ có thể vẫn tồn tại trong MySQL local vì `ddl-auto=update` không drop schema; chúng không còn được map hoặc sử dụng và có thể xóa thủ công sau khi sao lưu dữ liệu cần thiết.

## 2026-10-02 — Payment complaint và Manager review

Tạo bảng `payment_complaints` với quan hệ duy nhất tới Reservation, Payment và Customer. Bảng lưu trạng thái `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `REVIEW_OVERDUE`, lý do, hạn review, người/thời điểm xử lý, lý do quyết định và `version`.

Ảnh chứng minh tiếp tục dùng `file_assets`: Customer upload ảnh chưa liên kết, sau đó gửi các `imageIds`; service kiểm tra ảnh thuộc Customer, đúng MIME ảnh và chưa gắn entity trước khi liên kết `entity_type=PAYMENT_COMPLAINT`.

Reservation `PAYMENT_REVIEW` tiếp tục giữ capacity. Manager approve chuyển Payment `PAID` và Reservation `CONFIRMED`; reject hoặc Customer withdraw đặt `archived_at` và giải phóng capacity. Scheduler chỉ đánh dấu complaint quá 24 giờ là `REVIEW_OVERDUE`, không tự giải phóng capacity.

## 2026-10-04 — Reservation request fingerprint cho idempotency

- Thêm cột nullable `reservations.request_fingerprint` dài 64 ký tự để lưu SHA-256 của payload tạo Reservation đã chuẩn hóa.
- Reservation mới luôn lưu fingerprint gồm quote, goods condition, notes và toàn bộ goods items theo thứ tự request.
- Retry cùng `Idempotency-Key` và cùng payload trả lại Reservation cũ; cùng key nhưng payload khác trả `409 CONFLICT`.
- Cột giữ nullable để Hibernate `ddl-auto=update` tương thích với dữ liệu local đã tồn tại. Khi retry bản ghi cũ chưa có fingerprint, service tái dựng fingerprint từ Reservation và goods items đã lưu để so sánh.

## 2026-10-08 — Renewal operations / overdue coordination (rollout pending)

- Additive entities: `renewal_operation_states`, `renewal_operation_events`, `overdue_follow_up_states`, `overdue_follow_ups`.
- Shared Rental/Reservation/Payment/Refund/policy/permission table structures unchanged; no seed, rewrite or backfill.
- Review-only DDL: `docs/sql/renewal-operations-overdue-schema.sql`; NOT an enabled migration and NOT executed by this task.
- Runtime uses actual owner policy/accounting/calendar/assignment/evidence/hold/Recovery adapters. Until installed, related operations fail closed; no fake paid/balance/capacity/payout state.
- `ddl-auto=update` can create additive tables on startup. Do not start against team/shared DB until schema/lock/permission owners approve. H2 test schema creation is isolated and not production migration evidence.
- Contracts: `RENEWAL_OPERATIONS_API_CONTRACT.md`, `OVERDUE_API_CONTRACT.md`.

## 2026-10-08 — Support workflow (rollout pending)

- Five additive tables: `support_workflow_states`, `support_messages`, `support_workflow_events`, `support_escalations`, `support_command_receipts`.
- Reuse existing `support_tickets` / `SupportTicketStatus` unchanged; no shared table ALTER, policy/permission seed, delete, rewrite or legacy backfill.
- Workflow metadata holds version/reassignment revision, timestamps and follow-up references; messages explicitly distinguish PUBLIC/INTERNAL; command receipts enforce actor/operation/key idempotency.
- Review-only DDL: `docs/sql/support-workflow-schema.sql`; NOT an enabled migration and NOT executed by this task.
- File authorization/binding, SLA/calendar/close policy and trusted module results require owner adapters. Default STAFF lacks `MANAGE_SUPPORT`; no implicit grant in D5.
- `ddl-auto=update` may create additive tables on startup. Shared DB startup remains subject to schema/permission owner approval; isolated H2 tests are not MySQL/TiDB migration evidence.
- API contract: `SUPPORT_API_CONTRACT.md`. No public auto-close endpoint or automatically enabled scheduler.

## 2026-10-11 — Sổ tài chính hồ sơ thuê và hàng đợi thông báo

Migration: `src/main/resources/db/integration-migration/V2026101001__create_rental_ledger_and_notification_outbox.sql`.
Đã áp dụng vào DB test `storagehub_local` ngày 10/10/2026; **chưa áp dụng/xác minh trên DB chung của team**. Không sửa nội dung hoặc checksum migration đã áp dụng.

Thêm 7 bảng theo tên nghiệp vụ:

- `rental_ledger_accounts`: liên kết Rental/Customer/Facility và revision.
- `rental_ledger_obligations`: khoản phải thanh toán, hạn và nguồn nghĩa vụ.
- `rental_ledger_receipts`: khoản thu, phương thức và bằng chứng.
- `rental_ledger_allocations`: phân bổ khoản thu vào nghĩa vụ cùng Rental.
- `rental_ledger_refunds`: quyết định dành tiền hoàn và ghi nhận chi hoàn, không đồng nhất hai trạng thái.
- `rental_ledger_commands`: khóa chống xử lý trùng khi retry.
- `notification_outbox`: hàng đợi, retry, liên kết notification và biên nhận; đang chờ/đã tạo inbox không đồng nghĩa đã được người nhận xác nhận.

Chỉ CREATE bảng/index/CHECK/UNIQUE/FK, không ALTER/DROP bảng của Booking/Payment/Return/Auth, không cấp quyền, seed policy hoặc backfill số dư. JDBC dùng UUID CHAR(36); generated BINARY(16) nối FK tới ID bảng chung, không đổi kiểu ID hiện hữu. FK có thể hạn chế xóa bản ghi cha khi đã có ledger/outbox; phải kiểm thử flow xóa/lưu trữ trên bản sao DB đích.

Các bảng này dùng JDBC trực tiếp; `ddl-auto=update` không tự tạo chúng. Profile opt-in mới `rental-support-schema` đăng ký cả hai Flyway locations, giữ `ddl-auto=validate`, tắt clean/baseline-on-migrate và seed/backfill local. Ledger/communication không tự bật chỉ vì schema được tạo. Cấu hình mặc định, staging và prod hiện có không bị sửa để tự chạy migration này.

Profile yêu cầu `STORAGEHUB_DB_URL`, `STORAGEHUB_DB_USERNAME`, `STORAGEHUB_DB_PASSWORD` và tên DB mong đợi `STORAGEHUB_SCHEMA_ROLLOUT_DATABASE`. Trước Flyway, kiểm tra trực tiếp `SELECT VERSION(), DATABASE()` và tên engine trên chính connection; chỉ cho MySQL 8.4 đã được kiểm chứng, dừng nếu trỏ nhầm DB/TiDB/engine-version chưa chứng nhận. Nếu thấy bảng integration nhưng lịch sử/schema không đủ, dừng đối chiếu, không repair/reset/IF NOT EXISTS để che lỗi.

`application-prod.properties` hiện dùng `TiDbMySqlDialect`; đây là dấu hiệu cần xác minh DB đích, **không phải bằng chứng đã kiểm thử migration trên TiDB**. Chưa rollout trên TiDB hoặc production. MySQL DDL có thể auto-commit; sao lưu và thử trên bản sao DB chung trước, không hứa rollback DDL toàn bộ.

Quy trình và lệnh: [RENTAL_SUPPORT_SCHEMA_ROLLOUT.md](RENTAL_SUPPORT_SCHEMA_ROLLOUT.md). Test: `RentalSupportSchemaRolloutTests` (profile/identity/engine), `RentalIntegrationMySqlSchemaTests` (MySQL thật, migration/guard/FK/retry). Kết quả từng lần chạy phải ghi trong báo cáo, không coi test SKIP là PASS.

## 2026-10-11 — Payment gateway intent (migration opt-in)

Entity Payment yêu cầu gatewayIntentId nhưng migration nền chưa tạo cột. Bổ sung Java migration 2026101101 tại src/main/java/db/paymentmigration, location classpath:db/paymentmigration; profile rental-support-schema dùng đủ ba locations, không thay defaults/staging/prod hoặc migration cũ.

- gateway_intent_id VARCHAR(100), NOT NULL, full-column UNIQUE; giữ charset/collation và mã hiện có, tương thích writer SimulatedPaymentService.
- Chỉ NULL/blank nhận LEGACY-UNVERIFIED + hex Payment ID; đây không phải chứng cứ tiền thật hoặc simulation. Reader giữ UNVERIFIED; không đổi tiền/trạng thái/timestamps/idempotency/version, không seed/cấp quyền.
- Type/length/default/generated metadata lạ, trùng reference hoặc tên index xung đột: dừng trước sửa, không cắt/ghi đè chứng cứ. MySQL DDL có thể auto-commit; backup và kiểm thử bản sao vẫn cần thiết.
- Chỉ áp dụng DB test MySQL 8.4, chưa cập nhật storagehub_local/DB chung hoặc chứng nhận TiDB. Java migration checksum mặc định null; giữ version/source đã rollout bất biến.

Chi tiết dữ liệu cũ/rollout: [PAYMENT_GATEWAY_MIGRATION.md](PAYMENT_GATEWAY_MIGRATION.md). Test: 8 ca Payment migration MySQL và 6 ca full schema, cùng regression writer/profile/reader. Startup/API và giới hạn E2E ghi riêng trong báo cáo FE.

## 2026-10-11 — Đưa 7 bảng ledger/outbox vào Flyway chung

Theo phê duyệt, chuyển nguyên V2026101001__create_rental_ledger_and_notification_outbox.sql từ db/integration-migration vào db/migration. Version, checksum Flyway -1198625055 và SHA256 0E7AD29986D1CFB6E5E5DD3380CC464067EF16E83A13A52B3D0EAF370426766F không đổi. Không giữ SQL trùng, ALTER/DROP/seed/backfill số dư hoặc cấp quyền. Comment opt-in trong SQL là lịch sử, giữ để không sửa checksum.

BE dùng location mặc định sẽ phát hiện migration khi khởi động. DB đã áp dụng validate được ở vị trí mới, không chạy CREATE lại. Callback chỉ trước ledger pending kiểm engine MySQL 8.4 đã chứng nhận và bảng trùng; TiDB/engine-version chưa chứng nhận bị chặn trước DDL ledger. Những migration nền khác có thể đã chạy trước đó, nên backup/kiểm history/bản sao vẫn cần thiết.

Ledger/communication vẫn mặc định tắt; Payment 2026101101 vẫn opt-in. Profile rollout hiện dùng db/migration + db/paymentmigration. Không đổi defaults/staging/prod, ddl-auto, writer/Entity, quyền hoặc seed script. Chưa trực tiếp cập nhật DB chung. Các ghi chú opt-in ledger trước đây là lịch sử, thay thế bởi mục này.

Test PASS 60 ca: default discovery, upgrade giữ dữ liệu cũ, collision preflight, relocation history, full schema và JDBC regression. Báo cáo FE: docs/LEDGER_COMMON_MIGRATION_REPORT_20261011.md.
