# Database changelog

Tài liệu này ghi các thay đổi schema trong môi trường local/demo. Dự án hiện dùng:

```properties
spring.jpa.hibernate.ddl-auto=update
```

Không dùng Flyway trong phạm vi đồ án hiện tại. Trước production, các thay đổi tại đây phải được chuyển thành migration SQL có version và kiểm thử trên bản sao dữ liệu.

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
- `PaymentService` phải tìm Reservation tồn tại trước khi tạo payment intent.

Nếu database local đã có payment không có `reservation_id` hợp lệ, cần xóa dữ liệu payment demo cũ hoặc gắn lại đúng Reservation trước khi chạy backend.

## 2026-10-01 — Hoàn tất các bảng nền tảng booking

### `rental_package_policies`

Lưu gói thuê do BO cấu hình theo cơ sở: mã/tên gói, số tháng, tỷ lệ giảm, phiên bản chính sách, trạng thái và thời gian hiệu lực. Unique `(facility_id, code)` là ràng buộc nghiệp vụ, không phải index tối ưu hóa bổ sung.

### `payment_webhook_events`

Lưu provider, provider event ID, payment, payload, thời điểm xử lý và lỗi xử lý. Unique `(provider, provider_event_id)` ngăn cùng một webhook được áp dụng hai lần. `Payment` không còn lưu `provider_event_id` trực tiếp.

### `booking_documents`

Lưu một `BOOKING_CONFIRMATION` cho mỗi Reservation, liên kết tới `FileAsset`. Đây là chứng từ xác nhận booking sau thanh toán, không phải hợp đồng thuê đã ký.

### Thay đổi bảng hiện có

- `facilities`: bỏ timezone riêng; toàn hệ thống dùng `Asia/Ho_Chi_Minh`.
- `unit_types`: thêm code, số khung và ba kích thước khung; bỏ `price_per_m3`; diện tích/thể tích do Java tính từ kích thước; thêm version.
- `storage_units`: thêm `available_from`, `last_released_at` và version.
- `rentals`: dùng `contract_end_date`, thêm `actual_returned_at` và `completed_at` để không nhầm ngày dự kiến với ngày thực trả.
- `payments`: thêm provider request/transaction reference, paid/failure/refund fields và version.

Các cột cũ như `timezone`, `price_per_m3`, `area_m2`, `volume_m3` hoặc `rentals.end_date` có thể vẫn còn trong MySQL khi dùng `ddl-auto=update`, nhưng code không còn sử dụng. Nếu dựng database demo sạch, Hibernate chỉ tạo schema theo entity mới.
