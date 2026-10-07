# Cancelled Reservation Unit Release API contract

Contract này thuộc phạm vi vận hành của Trâm, xử lý physical storage unit đã được phân cho một reservation nhưng reservation sau đó chuyển sang `CANCELLED` trước khi check-in.

```text
Reservation CANCELLED
→ xác minh chưa check-in
→ xác minh chưa có Rental active
→ hủy assignment hiện hành
→ StorageUnit available hoặc maintenance
```

## 1. Phạm vi và nguyên tắc

- Base path: `/api/staff/cancelled-reservations`.
- Actor lấy từ JWT; request không nhận `userId`, role, facility hoặc trạng thái hiện tại của entity.
- Staff/Manager chỉ được đọc và xử lý reservation thuộc facility scope của mình.
- Client không được yêu cầu đổi `StorageUnit.status` trực tiếp. Client chỉ gửi quyết định xử lý sau khi đánh giá kho; BE tự xác định và thực hiện transition hợp lệ.
- Mọi mutation yêu cầu `Idempotency-Key`.
- Việc hủy assignment và cập nhật storage unit phải nằm trong cùng một database transaction.
- Không xóa assignment. Assignment được chuyển sang `CANCELLED` để giữ lịch sử.
- Không hoàn tiền trong module này. Refund/reconciliation thuộc payment flow riêng.
- Contract dùng đúng enum hiện tại của BE: `ReservationStatus.CANCELLED`, `RentalStatus.active`, `CheckInStatus.completed`, `StorageUnitStatus.available` và `StorageUnitStatus.maintenance`.

## 2. Điều kiện một reservation chờ giải phóng kho

Một reservation xuất hiện trong module khi đồng thời thỏa mãn:

1. `reservation.status = CANCELLED`.
2. Có assignment hiện hành ở trạng thái `ACTIVE`.
3. Assignment trỏ đến một physical storage unit.
4. Chưa có `CheckIn.status = completed` của reservation.
5. Chưa có `Rental.status = active` của reservation hoặc storage unit đó.

Reservation `CANCELLED` nhưng chưa từng được phân kho không cần xử lý trong module này.

## 3. Permission và facility scope

| Thao tác | Permission | Scope tối thiểu |
|---|---|---|
| Xem danh sách/chi tiết | `VIEW_RESERVATIONS` và `VIEW_UNITS` | `READ` |
| Đánh giá khả năng giải phóng | `VIEW_RESERVATIONS`, `VIEW_UNITS`, `VIEW_CHECKINS`, `VIEW_RENTALS` | `READ` |
| Giải phóng kho | `ASSIGN_UNITS` | `OPERATE` |
| Chuyển kho sang trạng thái chờ bảo trì | `ASSIGN_UNITS` | `OPERATE` |

`BUSINESS` và `ADMIN` vẫn phải có permission tương ứng. Nếu hệ thống quyết định hai role này không bị giới hạn facility scope thì authorization service áp dụng policy toàn hệ thống, không lấy facility từ request body.

## 4. Success và error envelope

Single-resource response:

```json
{
  "data": {},
  "correlationId": "uuid-or-safe-request-id"
}
```

List response:

```json
{
  "data": [],
  "pagination": {
    "page": 0,
    "pageSize": 20,
    "totalItems": 0,
    "totalPages": 0,
    "sort": "cancelledAt,asc"
  },
  "correlationId": "uuid-or-safe-request-id"
}
```

Error response dùng contract chung:

```json
{
  "error": {
    "code": "CONFLICT",
    "message": "The assigned unit cannot be released because check-in is completed",
    "details": {
      "reservationId": "uuid",
      "checkInId": "uuid"
    },
    "correlationId": "uuid-or-safe-request-id"
  }
}
```

## 5. Data projection dùng chung

### `UnitReleaseCaseResponse`

```json
{
  "reservationId": "uuid",
  "reservationCode": "RSV-20261002-001",
  "reservationStatus": "CANCELLED",
  "cancelledAt": "2026-10-02T03:15:00Z",
  "cancelReason": "Khách thay đổi kế hoạch",
  "facilityId": "uuid",
  "facilityName": "StorageHub Quận 7",
  "unitTypeId": "uuid",
  "assignment": {
    "assignmentId": "uuid",
    "status": "ACTIVE",
    "assignedAt": "2026-10-01T02:00:00Z",
    "storageUnitId": "uuid",
    "storageUnitCode": "S001",
    "storageUnitStatus": "reserved"
  },
  "eligibility": {
    "releasable": true,
    "reservationCancelled": true,
    "activeAssignmentPresent": true,
    "completedCheckInPresent": false,
    "activeRentalPresent": false,
    "blockingReasons": []
  }
}
```

`releasable = true` chỉ có nghĩa assignment có thể được kết thúc. Trạng thái đích của kho vẫn phụ thuộc `disposition` do staff ghi nhận.

## 6. Danh sách reservation chờ giải phóng kho

```http
GET /api/staff/cancelled-reservations/unit-releases
```

Query parameters:

| Tên | Bắt buộc | Mô tả |
|---|---:|---|
| `facilityId` | Không | Lọc theo facility; actor phải có scope |
| `unitTypeId` | Không | Lọc theo unit type |
| `q` | Không | Tìm theo reservation code, unit code hoặc customer |
| `blocked` | Không | `false` mặc định; `true` chỉ lấy case đang bị chặn |
| `page` | Không | Zero-based, mặc định `0` |
| `pageSize` | Không | Mặc định `20`, tối đa `100` |
| `sort` | Không | Mặc định `cancelledAt,asc` |

Response `200 OK` là `PageResponse<UnitReleaseCaseResponse>`.

Danh sách mặc định chỉ trả reservation có assignment `ACTIVE`. Case đã xử lý thành công không còn xuất hiện.

## 7. Xem chi tiết và kiểm tra eligibility

```http
GET /api/staff/cancelled-reservations/{reservationId}/unit-release
```

Response:

- `200 OK`: trả `UnitReleaseCaseResponse`, kể cả khi case đang bị chặn.
- `404 NOT_FOUND`: reservation không tồn tại, ngoài scope, hoặc chưa từng có assignment.

BE phải tính eligibility từ dữ liệu hiện tại; không dùng kết quả eligibility cũ do FE gửi lại.

Ví dụ case bị chặn:

```json
{
  "data": {
    "reservationId": "uuid",
    "reservationCode": "RSV-20261002-001",
    "reservationStatus": "CANCELLED",
    "assignment": {
      "assignmentId": "uuid",
      "status": "ACTIVE",
      "storageUnitId": "uuid",
      "storageUnitCode": "S001",
      "storageUnitStatus": "reserved"
    },
    "eligibility": {
      "releasable": false,
      "reservationCancelled": true,
      "activeAssignmentPresent": true,
      "completedCheckInPresent": true,
      "activeRentalPresent": false,
      "blockingReasons": ["CHECKIN_ALREADY_COMPLETED"]
    }
  },
  "correlationId": "uuid-or-safe-request-id"
}
```

Giá trị chuẩn của `blockingReasons`:

- `RESERVATION_NOT_CANCELLED`
- `ACTIVE_ASSIGNMENT_NOT_FOUND`
- `CHECKIN_ALREADY_COMPLETED`
- `ACTIVE_RENTAL_EXISTS`
- `UNIT_ASSIGNMENT_MISMATCH`
- `UNIT_STATE_NOT_RELEASABLE`
- `MAINTENANCE_TASK_ALREADY_OPEN`

## 8. Hủy assignment và giải phóng kho

```http
POST /api/staff/cancelled-reservations/{reservationId}/unit-release
Idempotency-Key: 7bd6b86e-96ca-4ef9-83ef-76c909060fe0
Content-Type: application/json
```

Request:

```json
{
  "assignmentId": "uuid",
  "disposition": "AVAILABLE",
  "reason": "Reservation đã hủy trước check-in"
}
```

`assignmentId` bắt buộc để chống việc staff mở case cũ nhưng reservation đã được gán sang assignment khác.

### 8.1. Disposition `AVAILABLE`

Chỉ dùng khi staff đã xác nhận unit không cần inspection, cleaning hoặc maintenance.

```json
{
  "assignmentId": "uuid",
  "disposition": "AVAILABLE",
  "reason": "Kho chưa được sử dụng và không cần xử lý"
}
```

Transition nguyên tử:

```text
Assignment ACTIVE → CANCELLED
StorageUnit reserved/assigned → available
Reservation.assignedUnit → null
```

### 8.2. Disposition `MAINTENANCE`

Áp dụng nếu unit cần inspection, cleaning hoặc maintenance. Module của Trâm chỉ khóa unit khỏi inventory bằng trạng thái `maintenance`; không tạo hay quản lý công việc bảo trì.

```json
{
  "assignmentId": "uuid",
  "disposition": "MAINTENANCE",
  "reason": "Không đưa kho trở lại inventory trước khi vệ sinh"
}
```

Transition nguyên tử:

```text
Assignment ACTIVE → CANCELLED
StorageUnit reserved/assigned → maintenance
Reservation.assignedUnit → null
```

Không được chuyển unit qua `available` ở bất kỳ thời điểm trung gian nào. Việc tạo task, inspection, cleaning, sửa chữa và chuyển `maintenance → available` thuộc module Maintenance của Phương.

### 8.3. Response thành công

`200 OK`:

```json
{
  "data": {
    "reservationId": "uuid",
    "reservationStatus": "CANCELLED",
    "assignmentId": "uuid",
    "assignmentStatus": "CANCELLED",
    "assignmentCancelledAt": "2026-10-02T03:30:00Z",
    "storageUnitId": "uuid",
    "storageUnitCode": "S001",
    "previousStorageUnitStatus": "reserved",
    "storageUnitStatus": "available",
    "disposition": "AVAILABLE",
    "processedBy": "uuid",
    "processedAt": "2026-10-02T03:30:00Z"
  },
  "correlationId": "uuid-or-safe-request-id"
}
```

Với `MAINTENANCE`, response chỉ xác nhận `storageUnitStatus = maintenance`; không trả hoặc tạo `MaintenanceTask`.

## 9. Validation và transition rules

BE phải lock và kiểm tra lại theo đúng thứ tự:

1. Lock reservation theo `reservationId`.
2. Xác minh `reservation.status = CANCELLED`.
3. Lock assignment theo `assignmentId` và xác minh assignment thuộc reservation.
4. Xác minh assignment là assignment hiện hành và có status `ACTIVE`.
5. Lock storage unit của assignment.
6. Xác minh reservation chưa có `CheckIn.status = completed`.
7. Xác minh reservation và unit chưa có `Rental.status = active`.
8. Xác minh unit chưa được gán cho assignment hiện hành khác.
9. Xác minh trạng thái unit là `reserved` hoặc `assigned`.
10. Hủy assignment, bỏ liên kết hiện hành và cập nhật unit theo disposition.
11. Ghi audit log và commit transaction.

Không dùng `StorageUnit.status = held` làm đầu ra của flow này.

## 10. Idempotency và concurrency

- Cùng actor, cùng `Idempotency-Key` và cùng payload trả lại response thành công ban đầu.
- Reuse key với payload khác trả `409 CONFLICT`.
- Nếu assignment đã `CANCELLED` bởi chính operation trước và kết quả hiện tại khớp disposition, retry trả kết quả cũ.
- Nếu assignment đã bị thay thế hoặc unit đã được gán cho reservation khác, trả `409 CONFLICT`; tuyệt đối không cập nhật assignment/unit mới.
- Reservation, assignment và unit phải có optimistic version hoặc được lấy bằng write lock.
- Unique constraint phải bảo đảm một reservation chỉ có tối đa một assignment `ACTIVE`, và một storage unit chỉ có tối đa một assignment `ACTIVE` tại một thời điểm.

## 11. HTTP status và error cases

| Trường hợp | HTTP | Code/message định hướng |
|---|---:|---|
| Đọc danh sách/chi tiết thành công | `200` | — |
| Release thành công hoặc retry hợp lệ | `200` | — |
| Payload/disposition/maintenance không hợp lệ | `400` | `VALIDATION_ERROR` |
| Chưa đăng nhập | `401` | `UNAUTHORIZED` |
| Thiếu permission hoặc facility scope | `403` | `FORBIDDEN` |
| Không tìm thấy hoặc resource ngoài scope | `404` | `NOT_FOUND` |
| Reservation chưa `CANCELLED` | `409` | `RESERVATION_NOT_CANCELLED` |
| Không có assignment hiện hành | `409` | `ACTIVE_ASSIGNMENT_NOT_FOUND` |
| Check-in đã hoàn tất | `409` | `CHECKIN_ALREADY_COMPLETED` |
| Có rental active | `409` | `ACTIVE_RENTAL_EXISTS` |
| Assignment/unit không còn khớp | `409` | `UNIT_ASSIGNMENT_MISMATCH` |
| Unit đã được dùng hoặc có trạng thái không hợp lệ | `409` | `UNIT_STATE_NOT_RELEASABLE` |
| Reuse idempotency key sai payload | `409` | `IDEMPOTENCY_CONFLICT` |

## 12. Audit và notification

Release thành công ghi một `ActivityLog`:

```text
action      = RESERVATION_UNIT_RELEASED
entityType  = UnitAssignment
entityId    = assignmentId
facilityId  = reservation.facilityId
beforeState = assignment ACTIVE + trạng thái cũ của unit
afterState  = assignment CANCELLED + available/maintenance
```

Notification tối thiểu:

- `AVAILABLE`: không bắt buộc gửi customer; có thể thông báo inventory đã được trả lại.
- `MAINTENANCE`: gửi staff/manager facility rằng unit cần xử lý.
- Không gửi nội dung kỹ thuật nội bộ cho customer.

## 13. Tích hợp với flow hủy reservation

API hủy reservation là chủ sở hữu transition sang `CANCELLED`; module này không tự đổi một reservation đang hoạt động sang `CANCELLED`.

Sau khi transaction hủy reservation commit:

- Nếu không có assignment hiện hành: kết thúc.
- Nếu có assignment hiện hành: case xuất hiện ở API danh sách chờ giải phóng.
- Staff đánh giá tình trạng unit và gọi API release với `AVAILABLE` hoặc `MAINTENANCE`.

Không tự động chuyển thẳng sang `available` ngay tại API cancel vì hệ thống chưa có quyết định inspection/cleaning/maintenance. Có thể tự động tạo work item/notification cho facility staff, nhưng release cuối cùng vẫn phải đi qua các invariant của contract này.

## 14. Data model tối thiểu cần bổ sung

### `UnitAssignment`

- `id`
- `reservationId`
- `storageUnitId`
- `status`: `ACTIVE`, `CANCELLED`, `COMPLETED`
- `assignedBy`, `assignedAt`
- `cancelledBy`, `cancelledAt`
- `cancelReason`
- `version`

### Repository requirements

- Lock reservation theo ID.
- Tìm và lock active assignment theo reservation.
- Lock storage unit theo ID.
- `exists` completed check-in theo reservation.
- `exists` active rental theo reservation hoặc storage unit.

## 15. Test contract tối thiểu

- Reservation `CANCELLED`, chưa check-in/rental: release về `available` thành công.
- Disposition `MAINTENANCE`: unit chuyển thẳng sang `maintenance`, không tạo maintenance task.
- Reservation chưa `CANCELLED`: `409`.
- Check-in đã `completed`: `409`, assignment và unit không đổi.
- Rental đang `active`: `409`, assignment và unit không đổi.
- Assignment ID cũ hoặc không thuộc reservation: `409`.
- Unit đã gán cho case khác: `409`.
- Staff ngoài facility scope: `403` hoặc `404` theo policy chống lộ dữ liệu.
- Retry cùng idempotency key/payload: trả kết quả cũ, không cập nhật unit lần hai.
- Reuse idempotency key với payload khác: `409`.
- Hai request đồng thời release cùng assignment: chỉ một request được áp dụng.
