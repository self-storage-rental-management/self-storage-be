# Check-in & Handover API contract (Trâm)

## Phạm vi và ranh giới nghiệp vụ

Module này tiếp nhận reservation đã có assignment hợp lệ, lên lịch check-in, ghi nhận khách không đến, đối chiếu hàng hóa và hoàn tất bàn giao vật lý.

Luồng trạng thái chính:

```text
Reservation UNIT_RESERVED
  -> schedule check-in
Reservation READY_FOR_CHECKIN + CheckIn scheduled
  -> complete handover
Reservation AWAITING_CUSTOMER_RECEIPT
Assignment COMPLETED
StorageUnit assigned
CheckIn completed
```

Điểm kết thúc của Trâm là `AWAITING_CUSTOMER_RECEIPT`. Module **không tạo hoặc kích hoạt Rental**; bước khách xác nhận nhận kho và chuyển sang `Rental ACTIVE` thuộc luồng nghiệp vụ tiếp theo.

## Quyền

- Xem danh sách: `VIEW_CHECKINS`.
- Lên lịch, no-show và hoàn tất bàn giao: `PERFORM_CHECKIN`.
- Dữ liệu luôn bị giới hạn theo facility của actor, trừ actor có quyền truy cập toàn hệ thống.

## 1. Danh sách hồ sơ check-in

`GET /api/staff/check-ins?facilityId={uuid}&q={text}&page=0&pageSize=20`

- Chỉ trả reservation ở `UNIT_RESERVED`, `READY_FOR_CHECKIN` hoặc `AWAITING_CUSTOMER_RECEIPT`.
- Reservation phải có unit và assignment `ACTIVE` hoặc `COMPLETED`.
- `q` tìm theo mã reservation, tên/email khách hàng hoặc mã unit.
- Kết quả dùng chuẩn `PageResponse<CheckInResponse>`.

## 2. Lên lịch check-in

`POST /api/staff/check-ins/reservations/{reservationId}/schedule`

```json
{
  "scheduledAt": "2026-10-07T03:00:00Z",
  "contractVerified": true,
  "paymentVerified": true,
  "note": "Hồ sơ đã đủ điều kiện nhận kho"
}
```

Điều kiện:

- Reservation đang `UNIT_RESERVED` hoặc `READY_FOR_CHECKIN`.
- Có assignment `ACTIVE` trỏ đúng unit đã giữ chỗ.
- Unit đang `reserved`.
- Chưa có Rental `ACTIVE`.
- `scheduledAt` không nằm trong quá khứ.
- Hai xác nhận hợp đồng và thanh toán bắt buộc là `true`.

Kết quả: tạo/cập nhật `CheckIn scheduled` và chuyển reservation sang `READY_FOR_CHECKIN`.

## 3. Upload bằng chứng bàn giao

`POST /api/files` với `multipart/form-data`:

- `file`: JPEG, PNG, WebP hoặc PDF.
- `entityType`: `CHECK_IN`.
- `entityId`: ID của check-in.

Lấy `data.id` từ response để đưa vào `evidenceReferences` khi hoàn tất bàn giao.

## 4. Hoàn tất check-in và bàn giao

`POST /api/staff/check-ins/{checkInId}/complete`

```json
{
  "checklist": {
    "identityVerified": true,
    "reservationMatched": true,
    "contractVerified": true,
    "paymentVerified": true,
    "measurementVerified": true,
    "unitWalkthrough": true,
    "conditionRecorded": true,
    "accessHandedOver": true
  },
  "actualMeasurements": {
    "lengthCm": 100,
    "widthCm": 80,
    "heightCm": 60,
    "weightKg": 50,
    "actualVolumeM3": 0.48,
    "varianceAccepted": true
  },
  "initialUnitCondition": "Kho sạch, khóa và đèn hoạt động bình thường",
  "goodsCondition": "Hàng nguyên vẹn khi tiếp nhận",
  "packageCount": 3,
  "goodsCategory": "Đồ gia dụng",
  "evidenceReferences": ["0d29e3dd-67a8-4c8c-bccd-d54e374f50b0"],
  "handedOverItems": ["PIN truy cập", "Biên nhận bàn giao"],
  "notes": ""
}
```

Điều kiện:

- Check-in đang `scheduled`; gọi lại check-in đã `completed` trả về kết quả hiện tại để bảo đảm idempotent.
- Reservation đang `READY_FOR_CHECKIN`.
- Assignment còn `ACTIVE`, unit khớp reservation và đang `reserved`.
- Chưa có Rental `ACTIVE`.
- Tất cả 8 mục checklist phải hoàn tất.
- Kích thước, thể tích và khối lượng thực tế không vượt sức chứa của unit; thứ tự dài/rộng/cao được so theo bộ kích thước đã sắp xếp để không phụ thuộc hướng đặt hàng.
- Có ít nhất một bằng chứng và một vật dụng/quyền truy cập đã bàn giao.

Kết quả được thực hiện trong một transaction:

- `CheckIn -> completed`, lưu thời điểm/người thực hiện và snapshot handover JSON.
- `UnitAssignment -> COMPLETED`.
- `Reservation -> AWAITING_CUSTOMER_RECEIPT`.
- `StorageUnit -> assigned`.
- Không tạo Rental.

## 5. Khách không đến

`POST /api/staff/check-ins/{checkInId}/no-show`

```json
{
  "reason": "Khách không đến theo lịch hẹn"
}
```

- Chỉ áp dụng cho check-in `scheduled` khi thời điểm hẹn đã đến hoặc đã qua.
- Chuyển `CheckIn -> no_show`.
- Không hủy assignment, không nhả unit và không đổi trạng thái reservation.
- Có thể lên lịch lại trên cùng hồ sơ check-in.

Nếu reservation bị hủy trước khi hoàn tất handover, luồng release unit của Trâm sẽ đồng thời chuyển check-in còn `scheduled` sang `cancelled`; check-in `completed` tiếp tục là điều kiện chặn release.

## Audit

Các mutation phát sinh audit event: `CHECKIN_SCHEDULED`, `CHECKIN_NO_SHOW`, `CHECKIN_HANDOVER_COMPLETED`. Mỗi event ghi actor, entity, dữ liệu trước/sau và correlation ID theo cơ chế audit chung.
