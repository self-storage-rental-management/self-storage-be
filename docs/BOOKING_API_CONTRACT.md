# Booking API contract

Nguồn contract chính cho phạm vi của Thảo:

```text
Discovery → Goods → Quote → Reservation → Payment → Reservation CONFIRMED
```

## 1. Quy ước chung

- Base path cho Customer: `/api/customer`.
- Customer lấy từ JWT; request không nhận `customerId`, role hoặc reservation status.
- Tiền dùng `VND`, biểu diễn bằng số nguyên, không nhận tổng tiền chính thức từ FE.
- Kích thước kiện dùng `cm`; khối lượng dùng `kg` và là khối lượng mỗi kiện.
- Khoảng thuê dùng `[startDate, endDate)`.
- BE tính lại compatibility, giá, giảm giá, tiền cọc và capacity khi tạo reservation.
- Cọc giữ chỗ online bằng `40%` tổng tiền thuê sau giảm giá và được trừ vào tiền thuê.
- Tiền đảm bảo kho bằng `1 tháng` giá thuê gốc, được thu riêng tại Check-in.
- Các mutation có khả năng retry phải nhận `Idempotency-Key`.
- Lỗi chuyển trạng thái hoặc reuse idempotency key sai payload trả `409 CONFLICT`.

## 2. Discovery

```http
GET /api/facilities
GET /api/facilities/{facilityId}
GET /api/facilities/{facilityId}/unit-types?startDate=&endDate=
GET /api/unit-types/{unitTypeId}
```

Customer chỉ thấy Facility và Unit Type đang hoạt động. Unit Type trả cấu hình kích thước kho, tải trọng, giá tháng, kích thước khung, số khung và `availableCount`. `areaM2` và `volumeM3` là giá trị BE tính, không phải input độc lập.

## 3. Goods và compatibility

Mọi hàng hóa dùng chung một cấu trúc; `OTHER` chỉ bổ sung tên và vật liệu tùy chỉnh.

```json
{
  "facilityId": "uuid",
  "unitTypeId": "uuid",
  "startDate": "2026-10-10",
  "endDate": "2027-01-10",
  "goodsCondition": "Đóng gói nguyên vẹn",
  "goodsItems": [
    {
      "category": "FURNITURE",
      "customGoodsName": null,
      "materialName": "Gỗ",
      "customMaterial": null,
      "description": "Bàn đã tháo chân",
      "customerNote": null,
      "quantity": 2,
      "lengthCm": 120,
      "widthCm": 60,
      "heightCm": 15,
      "weightPerItemKg": 18,
      "fragile": false
    }
  ]
}
```

```http
POST /api/customer/reservations/compatibility-check
```

Quy tắc:

- `quantity` là số nguyên dương; kích thước và khối lượng phải lớn hơn `0`.
- `OTHER` bắt buộc `customGoodsName` và `customMaterial`.
- Ảnh/mô tả/ghi chú là tùy chọn cho mọi loại hàng.
- Sau khi Reservation và dòng hàng đã được tạo, ảnh được upload bằng `POST /api/files` với `entityType=RESERVATION_GOODS_ITEM` và `entityId` là ID của dòng hàng.
- BE tự tính tổng thể tích và tổng khối lượng.
- Compatibility dùng kích thước và số khung do Unit Type quản lý; không dùng door-fit hoặc giá theo mét khối.
- Preview không giữ capacity và không được dùng thay validation lúc tạo reservation.

## 4. Quote

```http
POST /api/customer/reservations/quote
```

Request dùng cùng kỳ thuê và `goodsItems` của compatibility. Response tối thiểu:

```json
{
  "data": {
    "quoteId": "uuid",
    "facilityId": "uuid",
    "unitTypeId": "uuid",
    "startDate": "2026-10-10",
    "endDate": "2027-01-10",
    "rentalMonths": 3,
    "monthlyPrice": 5500000,
    "subtotal": 16500000,
    "discountRate": 0.03,
    "discountAmount": 495000,
    "totalAfterDiscount": 16005000,
    "reservationDepositAmount": 6402000,
    "securityDepositAmount": 5500000,
    "remainingRentalAmount": 9603000,
    "dueAtCheckIn": 15103000,
    "totalInitialObligation": 21505000,
    "policyVersion": "string",
    "quotedAt": "instant",
    "expiresAt": "instant"
  },
  "correlationId": "string"
}
```

Policy gói thuê do BO quản lý theo cơ sở và số tháng. Create reservation phải tính lại quote và lưu snapshot bất biến. Hệ thống chỉ dùng VND. Snapshot lưu riêng cọc giữ chỗ 40%, tiền đảm bảo kho, tiền thuê còn lại 60%, số tiền thu tại Check-in và tổng nghĩa vụ ban đầu.

## 5. Reservation

```http
POST /api/customer/reservations
GET  /api/customer/reservations
GET  /api/customer/reservations/{reservationId}
POST /api/customer/reservations/{reservationId}/cancel
```

Create yêu cầu `Idempotency-Key`, nhận `quoteId`, thông tin lịch check-in đã chốt và khai báo hàng hóa. BE khóa/tính lại capacity, compatibility và giá trong transaction. Reservation mới có trạng thái `AWAITING_EMAIL`.

Customer chỉ đọc hoặc hủy reservation của chính mình. Customer không được tự chuyển status.

## 6. Xác minh email và duyệt OTHER

```http
POST /api/customer/reservations/{reservationId}/email-verification
POST /api/customer/reservations/{reservationId}/email-verification/resend
```

Sau khi xác minh:

```text
không có OTHER → AWAITING_PAYMENT
có OTHER       → AWAITING_REVIEW
```

Staff approve `OTHER` chuyển sang `AWAITING_PAYMENT`; reject chuyển `REJECTED`. Endpoint Staff sẽ được chốt trong module review, không đặt trong Customer controller.

```http
GET  /api/staff/reservation-reviews
POST /api/staff/reservation-reviews/{reservationId}/decision
```

Request xử lý review:

```json
{
  "decision": "APPROVE",
  "note": "Hàng hóa phù hợp điều kiện lưu kho"
}
```

`REJECT` bắt buộc phải có `note`. Staff/Manager phải có quyền theo cơ sở; Business/Admin có thể xử lý toàn hệ thống. Sau khi approve, khách có 10 phút để thanh toán cọc.

Trong phạm vi đồ án, Reservation chỉ lưu kết quả duyệt hiện tại và người/thời điểm duyệt; không tạo bảng lịch sử review riêng. Khi Staff xử lý, service kiểm tra `goodsReviewStatus = PENDING` bằng điều kiện `if` trước khi cập nhật.

## 7. Payment

```http
POST /api/customer/reservations/{reservationId}/payment-intent
GET  /api/customer/reservations/{reservationId}/payment
POST /api/webhooks/payments/{provider}
```

Booking chỉ dùng:

```text
method   = ONLINE_GATEWAY
provider = MOMO
purpose  = RESERVATION_DEPOSIT
```

Payment intent chỉ được tạo khi reservation là `AWAITING_PAYMENT`, email đã xác minh, review hợp lệ và hold chưa hết hạn. Amount lấy từ pricing snapshot; FE không gửi amount chính thức.

Webhook hợp lệ và idempotent chuyển payment sang `PAID`. Khi reservation vẫn confirmable:

```text
Payment PAID + Reservation AWAITING_PAYMENT
→ Reservation CONFIRMED
```

Nếu tiền đã thu nhưng reservation không thể xác nhận thì payment chuyển `REFUND_PENDING` hoặc `RECONCILIATION_REQUIRED`; không tự động phục hồi reservation trái quy tắc.

## 8. State contract

Luồng booking:

```text
AWAITING_EMAIL → AWAITING_PAYMENT → CONFIRMED
AWAITING_EMAIL → AWAITING_REVIEW → AWAITING_PAYMENT → CONFIRMED
AWAITING_REVIEW → REJECTED
AWAITING_EMAIL/REVIEW/PAYMENT → CANCELLED hoặc EXPIRED
```

Ranh giới bàn giao module tiếp theo:

```text
CONFIRMED → UNIT_RESERVED → READY_FOR_CHECKIN
→ AWAITING_CUSTOMER_RECEIPT → COMPLETED
```

Các transition khác bị từ chối bằng `409 CONFLICT`. Khi triển khai từng chức năng, service phụ trách phải kiểm tra trạng thái hiện tại bằng điều kiện rõ ràng trước khi cập nhật.

Scheduler kiểm tra reservation quá hạn mỗi 60 giây. Các trạng thái `AWAITING_EMAIL`, `AWAITING_REVIEW`, `AWAITING_PAYMENT` có `holdExpiresAt` đã qua sẽ chuyển sang `EXPIRED`. Payment còn `PENDING` hoặc `PROCESSING` của reservation đó cũng chuyển `EXPIRED`, đồng thời hệ thống ghi audit log và tạo notification cho khách hàng.

Ví dụ khi xác nhận thanh toán:

```java
if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) {
    throw ApiExceptions.conflict("Đơn đặt kho không ở trạng thái chờ thanh toán");
}

reservation.setStatus(ReservationStatus.CONFIRMED);
```

Cách này giữ kiến trúc ở mức cơ bản `Controller → Service → Repository → Entity`, dễ theo dõi và trình bày. Test chuyển trạng thái sẽ được viết cùng service nghiệp vụ tương ứng, thay vì tạo một state-machine abstraction riêng.

## 9. HTTP status tối thiểu

| Trường hợp | HTTP |
|---|---:|
| Thành công GET/preview/quote | `200` |
| Tạo reservation/payment intent | `201` hoặc contract endpoint đã chốt |
| Request sai | `400` |
| Chưa đăng nhập | `401` |
| Không sở hữu/không có quyền | `403` hoặc `404` theo policy chống lộ dữ liệu |
| Không tìm thấy | `404` |
| Hết capacity, transition sai, idempotency conflict | `409` |

## 10. Ngoài phạm vi contract này

- BO tạo Facility, Unit Type, physical unit và pricing package.
- Manager phân physical unit sau `CONFIRMED`.
- Staff check-in và handover.
- Renewal, overdue, return, settlement và maintenance.
- Hoàn cọc khi khách đã được giữ/phân kho rồi tự hủy trước check-in: không hoàn theo quyết định nghiệp vụ đã chốt.
