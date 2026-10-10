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
- Mỗi kiện phải lọt vào một khung theo ít nhất một trong 6 hướng xoay.
- Các dòng hàng được gộp chung theo tổng thể tích, không mặc định mỗi loại hàng chiếm một khung riêng.
- `usableVolumePerRackM3 = rackLengthM × rackWidthM × rackHeightM × 0.80`.
- `requiredRackCount = ceil(totalGoodsVolumeM3 / usableVolumePerRackM3)` và phải không vượt quá `unitRackCount`.
- Compatibility response trả thêm `rackUtilizationRate`, `usableVolumePerRackM3`, `requiredRackCount` và `unitRackCount`; FE chỉ preview, kết quả BE là chính thức.
- Preview không giữ capacity và không được dùng thay validation lúc tạo reservation.

## 4. Quote

```http
GET  /api/customer/reservations/rental-packages?facilityId={uuid}&startDate={date}
POST /api/customer/reservations/quote
```

Customer lấy danh sách gói đang hoạt động và còn hiệu lực theo cơ sở/ngày bắt đầu, sau đó gửi nguyên `code` của gói đã chọn vào `pricingPackageCode`. Frontend không tự suy đoán mã policy từ số tháng.

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

Customer được hủy reservation ở `AWAITING_EMAIL`, `AWAITING_REVIEW`, `AWAITING_PAYMENT`, `PAYMENT_GRACE`, `CONFIRMED`, `UNIT_RESERVED` hoặc `READY_FOR_CHECKIN`. Ba trạng thái cuối chỉ được hủy trước check-in. Nếu cọc đã được ghi nhận, Payment giữ `PAID` và cọc 40% không hoàn lại; không tạo quy trình refund provider. Payment `PENDING` được chuyển `CANCELLED`. Nếu Payment đang `PROCESSING`, service phải hoàn tất hoặc thất bại thao tác mô phỏng trước khi cho hủy.

Ranh giới module: booking service không xóa `assignedUnit` và không cập nhật `StorageUnit.status`. Nếu đã phân unit, module Assignment/Check-in của Trâm nhận trách nhiệm hủy assignment và chỉ đưa physical unit về `AVAILABLE` sau khi xác nhận chưa check-in, chưa có Rental `ACTIVE` và không có inspection/cleaning/maintenance đang chặn.

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

## 7. Payment mô phỏng và khiếu nại

```http
POST /api/customer/reservations/{reservationId}/simulated-payment
GET  /api/customer/reservations/{reservationId}/payment
POST /api/customer/reservations/{reservationId}/payment-complaints
GET  /api/customer/reservations/{reservationId}/payment-complaint
POST /api/customer/payment-complaints/{complaintId}/withdraw
GET  /api/manager/payment-complaints
GET  /api/manager/payment-complaints/review-queue
POST /api/manager/payment-complaints/{complaintId}/decision
```

Payment mô phỏng chỉ được tạo khi reservation là `AWAITING_PAYMENT`; amount luôn lấy từ pricing snapshot, FE không gửi amount hoặc tự đặt `PAID`.

Customer UI chỉ có nút `Thanh toán`. Nút gọi endpoint mô phỏng, bị disable trong lúc xử lý và không cho Customer chọn outcome. Response trả `SUCCESS`, `FAILED` hoặc `NOT_RECEIVED`; FE hiển thị thông báo rồi refetch Payment/Reservation. Outcome được cấu hình có kiểm soát tại BE cho local/demo, không chọn ngẫu nhiên và không nhận từ request Customer.

Khi đến `paymentExpiresAt`, Reservation chuyển `PAYMENT_GRACE`, đặt `complaintExpiresAt = paymentExpiresAt + 30 phút` và vẫn giữ capacity. Trong cửa sổ này Customer có thể `Hủy đơn` để chuyển `CANCELLED` và giải phóng ngay, hoặc gửi một Complaint đang hoạt động với reason và ít nhất một ảnh. Hết 30 phút không có Complaint thì chuyển `EXPIRED`, giải phóng capacity và archive.

Complaint hợp lệ chuyển Reservation sang `PAYMENT_REVIEW`, đặt `reviewDueAt = submittedAt + 24h` và tiếp tục giữ capacity. Customer có thể rút Complaint chỉ khi `PENDING/REVIEW_OVERDUE`; transition là Complaint `WITHDRAWN` + Reservation `CANCELLED`. Manager approve chuyển Complaint `APPROVED`, Payment `PAID`, Reservation `CONFIRMED`; reject bắt buộc reason, chuyển Complaint `REJECTED`, Payment `NOT_RECEIVED`, Reservation `REJECTED`, notification và archive.

Ảnh complaint được upload trước bằng `POST /api/files` không gắn entity, sau đó Customer gửi `imageIds` trong request tạo complaint. BE chỉ liên kết file ảnh do đúng Customer upload và chưa thuộc entity khác. Manager queue yêu cầu role `MANAGER|BUSINESS|ADMIN`, permission `view_payments/manage_payments` và facility scope phù hợp.

Response Complaint vẫn trả `imageIds` để tương thích, đồng thời trả `images[]` gồm tên file, MIME, kích thước và `downloadUrl`. Ảnh được xem/tải bằng `GET /api/files/{fileId}`. API chỉ cho uploader tải file của mình; với ảnh `PAYMENT_COMPLAINT`, Manager/Business/Admin phải có `view_payments` và đúng facility scope. Không có public file URL.

`review-queue` là danh sách tổng hợp: `REVIEW_OVERDUE` trước, Complaint `PENDING` theo `reviewDueAt`, Reservation `PAYMENT_GRACE`, sau đó các Reservation chưa archive theo `createdAt` mới nhất. Response trả `priority` để FE giữ đúng thứ tự nhưng không có physical unit code.

Không dùng HTTP DELETE cho nghiệp vụ này. Mọi thao tác dùng lock, `@Version`, conditional transition, audit và trả `409` khi Customer/Manager xử lý cạnh tranh.

Một Customer không được tạo thêm reservation trùng Facility, Unit Type và kỳ thuê khi đã có hold còn giữ capacity. Kiểm tra này chạy sau khi lock Unit Type và trước insert để hạn chế giữ kho giả; request lặp đúng `Idempotency-Key` vẫn trả reservation đã tạo. Ngoài ra, mỗi Customer được tạo tối đa 5 reservation mới mỗi giờ và 5 payment complaint mỗi 24 giờ; idempotent retry không bị tính như request mới.

## 8. Booking confirmation document

```http
POST /api/customer/reservations/{reservationId}/booking-document
GET  /api/customer/reservations/{reservationId}/booking-document
GET  /api/customer/reservations/{reservationId}/booking-document/download
```

Chỉ Customer sở hữu reservation đã `CONFIRMED` hoặc đã đi vào state downstream mới được tạo/xem/tải. `POST` là idempotent: nếu đã có document thì trả lại cùng record. Nội dung lấy từ Reservation và immutable pricing snapshot, lưu PDF vào storage, đồng thời lưu `FileAsset` với SHA-256 và `BookingDocument(type=BOOKING_CONFIRMATION)`. Tài liệu ghi rõ đây là xác nhận booking, không phải hợp đồng thuê đã ký và không chứa physical unit trước khi Trâm phân kho.

## 9. State contract

Luồng booking:

```text
AWAITING_EMAIL → AWAITING_PAYMENT → CONFIRMED
AWAITING_EMAIL → AWAITING_REVIEW → AWAITING_PAYMENT → CONFIRMED
AWAITING_PAYMENT → PAYMENT_GRACE → PAYMENT_REVIEW → CONFIRMED/REJECTED
PAYMENT_GRACE → CANCELLED/EXPIRED
PAYMENT_REVIEW → CANCELLED khi Customer rút Complaint
AWAITING_REVIEW → REJECTED
AWAITING_EMAIL/REVIEW/PAYMENT → CANCELLED hoặc EXPIRED
CONFIRMED/UNIT_RESERVED/READY_FOR_CHECKIN → CANCELLED trước check-in, không hoàn cọc
```

Ranh giới bàn giao module tiếp theo:

```text
CONFIRMED → UNIT_RESERVED → READY_FOR_CHECKIN
→ AWAITING_CUSTOMER_RECEIPT → COMPLETED
```

Các transition khác bị từ chối bằng `409 CONFLICT`. Khi triển khai từng chức năng, service phụ trách phải kiểm tra trạng thái hiện tại bằng điều kiện rõ ràng trước khi cập nhật.

Scheduler kiểm tra reservation quá hạn mỗi 60 giây. `AWAITING_EMAIL` và review chưa hoàn tất có thể chuyển `EXPIRED` theo deadline riêng. `AWAITING_PAYMENT` đến `paymentExpiresAt` phải chuyển `PAYMENT_GRACE`; chỉ khi hết thêm 30 phút mà không có Complaint mới chuyển `EXPIRED`, archive và giải phóng capacity. Complaint `PENDING` quá `reviewDueAt` chuyển `REVIEW_OVERDUE`, tiếp tục giữ capacity và lên đầu hàng đợi cho đến khi Manager quyết định hoặc Customer rút. Job dùng lock/conditional update để không tranh chấp với cancel, withdraw hoặc decision.

Ví dụ khi xác nhận thanh toán:

```java
if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) {
    throw ApiExceptions.conflict("Đơn đặt kho không ở trạng thái chờ thanh toán");
}

reservation.setStatus(ReservationStatus.CONFIRMED);
```

Cách này giữ kiến trúc ở mức cơ bản `Controller → Service → Repository → Entity`, dễ theo dõi và trình bày. Test chuyển trạng thái sẽ được viết cùng service nghiệp vụ tương ứng, thay vì tạo một state-machine abstraction riêng.

## 10. HTTP status tối thiểu

| Trường hợp | HTTP |
|---|---:|
| Thành công GET/preview/quote | `200` |
| Tạo reservation/payment intent | `201` hoặc contract endpoint đã chốt |
| Request sai | `400` |
| Chưa đăng nhập | `401` |
| Không sở hữu/không có quyền | `403` hoặc `404` theo policy chống lộ dữ liệu |
| Không tìm thấy | `404` |
| Hết capacity, transition sai, idempotency conflict | `409` |

## 11. Ngoài phạm vi contract này

- BO tạo Facility, Unit Type, physical unit và pricing package.
- Manager phân physical unit sau `CONFIRMED`.
- Staff check-in và handover.
- Renewal, overdue, return, settlement và maintenance.
- Hoàn cọc khi khách đã được giữ/phân kho rồi tự hủy trước check-in: không hoàn theo quyết định nghiệp vụ đã chốt.
