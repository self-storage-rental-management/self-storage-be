# Check-in & Handover API contract (Trâm)

## Phạm vi và ranh giới

Trâm xử lý reservation đã được duyệt và đã có unit assignment: chuẩn bị check-in, đối chiếu hàng hóa, cấp quyền truy cập và bàn giao vật lý.

```text
CONFIRMED
  → UNIT_RESERVED (Assignment)
  → READY_FOR_CHECKIN (customer appointment + CheckIn scheduled)
  → AWAITING_CUSTOMER_RECEIPT (Staff handover completed)
```

`Check-in & Handover` không tạo Rental và không chuyển Rental sang ACTIVE. Khách hàng xác nhận đã nhận kho ở flow kế tiếp. Khi đó, credential đang ACTIVE mới được liên kết với Rental. Trâm kết thúc ở `Reservation.AWAITING_CUSTOMER_RECEIPT`, `CheckIn.completed`, `UnitAssignment.COMPLETED` và `StorageUnit.assigned`.

## Phân quyền actor

| Nghiệp vụ | Actor | Permission/scope |
|---|---|---|
| Xem tiến trình reservation, goods và duyệt hồ sơ | Staff | `VIEW_RESERVATIONS`, `APPROVE_RESERVATIONS`, scope `OPERATE` |
| Assignment | Actor có quyền assignment theo contract Assignment | `ASSIGN_UNITS`, facility scope |
| Xem check-in/handover | Staff hoặc Manager | `VIEW_CHECKINS`, facility scope `READ` |
| Lên lịch, no-show, reject, hoàn tất handover | Staff | `PERFORM_CHECKIN`, facility scope `OPERATE` |
| Cấp hoặc thu hồi credential | **Staff** | `PERFORM_CHECKIN` khi cấp. `PROCESS_RETURNS` khi thu hồi. Scope `OPERATE` |
| Theo dõi lịch sử credential | Staff hoặc Manager | `VIEW_CHECKINS`, scope `READ`. Manager không có endpoint mutation |

Manager chỉ giám sát lịch sử và trạng thái credential. API cấp/thu hồi từ chối actor Manager dù Manager có quyền xem check-in.

## 1. Danh sách hồ sơ check-in

`GET /api/staff/check-ins?facilityId={uuid}&q={text}&status={status}&scheduledFrom={instant}&scheduledTo={instant}&page=0&pageSize=20`

- Chỉ trả reservation ở `UNIT_RESERVED`, `READY_FOR_CHECKIN` hoặc `AWAITING_CUSTOMER_RECEIPT` có assignment hợp lệ.
- `q` tìm theo mã reservation, khách hàng, email hoặc mã unit.
- `status`: `UNSCHEDULED`, `scheduled`, `no_show`, `completed`.
- Lịch hiển thị lấy từ `Reservation.appointmentAt`, do Customer đặt. Staff không được đổi giá trị này.
- Reservation cũ chưa có `appointmentAt` được hỗ trợ một lần qua request tương thích khi schedule. Dữ liệu sau đó được ghim vào reservation. Reservation mới phải có appointment của Customer.

Response dùng `PageResponse<CheckInResponse>`.

## 2. Schedule check-in

`POST /api/staff/check-ins/reservations/{reservationId}/schedule`

```json
{
  "scheduledAt": "2026-10-07T03:00:00Z",
  "contractVerified": true,
  "paymentVerified": true,
  "note": "Hồ sơ đã đủ điều kiện nhận kho"
}
```

- Reservation phải là `UNIT_RESERVED` hoặc `READY_FOR_CHECKIN`, có assignment `ACTIVE` và unit `reserved`.
- Với reservation có `appointmentAt`, BE bỏ qua `scheduledAt` của Staff và dùng đúng lịch Customer đã đặt. Không áp dụng quy tắc “lịch phải ở tương lai”. Khách đến sớm vẫn được bàn giao.
- Hai cờ hợp đồng/thanh toán là xác nhận nghiệp vụ, không thay đổi dữ liệu hợp đồng hoặc payment của actor khác.
- Kết quả: tạo/cập nhật `CheckIn.scheduled`, chuyển reservation sang `READY_FOR_CHECKIN`.

## 3. Issue credential trong Bàn giao & Nhận kho

Staff cấp một hoặc nhiều loại: `PIN` (access code), `CARD`, `KEY`, `LOCK`.

`POST /api/staff/check-ins/{checkInId}/credentials`

```json
{
  "type": "PIN",
  "reference": "PIN-2048",
  "secret": "2048",
  "note": "Cấp tại quầy nhận kho"
}
```

Điều kiện:

- Actor phải là Staff, có `PERFORM_CHECKIN` và scope `OPERATE`.
- Check-in đang `scheduled`, reservation `READY_FOR_CHECKIN`, assignment còn `ACTIVE`, unit `reserved`, chưa có Rental `ACTIVE`.
- Không được có hai credential ACTIVE cùng loại cho một reservation. Request lặp lại cùng `type + reference` trả lại credential hiện tại (idempotent).

BE lưu bắt buộc `issuedAt` và `issuedBy`. `reference` là số seri hoặc mã hiển thị. `secret` chỉ lưu SHA-256 và không trả PIN thô. Credential liên kết với reservation, facility, unit và customer. Khi Customer xác nhận receipt tạo Rental, credential được liên kết thêm với Rental.

Checklist handover phải có `accessHandedOver = true`. BE yêu cầu reservation có ít nhất một credential ACTIVE trước khi hoàn tất.

## 4. Upload hợp đồng và bằng chứng

`POST /api/files` với `entityType=CHECK_IN`, `entityId={checkInId}`. File hợp đồng đã ký là bắt buộc trong UI Staff và được đưa vào `evidenceReferences` cùng ảnh/PDF hiện trạng.

`GET /api/files/{assetId}` cho actor có quyền đọc check-in và facility tương ứng.

Customer xem lại các tệp đã bàn giao bằng `GET /api/customer/reservations/{reservationId}/check-in-documents`. Mỗi `assetId` trả về được mở hoặc tải qua `GET /api/files/{assetId}` sau khi hệ thống kiểm tra reservation thuộc về Customer đó. UI hiển thị tệp đầu tiên là hợp đồng đã ký và các tệp tiếp theo là bằng chứng bàn giao.

## 5. Hoàn tất physical handover

`POST /api/staff/check-ins/{checkInId}/complete`

Request gồm checklist 8 mục, số đo thực tế, hàng hóa, `evidenceReferences`, `handedOverItems` và ghi chú như contract trước đây.

Điều kiện chính:

- Check-in `scheduled`, reservation `READY_FOR_CHECKIN`, assignment `ACTIVE`, unit `reserved`, không có Rental `ACTIVE`.
- 8 checklist đều `true`, có bằng chứng hợp lệ và số đo không vượt capacity. Variance quá 5% phải có chấp nhận và lý do.
- Có ít nhất một credential ACTIVE đã cấp.

Transaction transition:

```text
CheckIn scheduled → completed (checkedInAt, performedBy)
UnitAssignment ACTIVE → COMPLETED
Reservation READY_FOR_CHECKIN → AWAITING_CUSTOMER_RECEIPT
StorageUnit reserved → assigned
```

Không tạo Rental tại endpoint này.

## 6. Cảnh báo cho Nhiệm vụ hằng ngày

`GET /api/staff/credential-alerts?facilityId={uuid}`

Response là mảng `CredentialAlert`:

- `action=ISSUE`: check-in đã sẵn sàng nhưng chưa có credential ACTIVE. Staff phải cấp trong Bàn giao & Nhận kho.
- `action=REVOKE`: return case đang mở có credential ACTIVE. Staff phải thu hồi trong Nghiệm thu Trả kho.

Màn hình `Nhiệm vụ hằng ngày` hiển thị hai loại cảnh báo này cùng deadline/lý do và điều hướng tới đúng màn hình nghiệp vụ.

## 7. Nghiệm thu Trả kho và revoke credential

Khi Staff thực hiện `POST /api/staff/returns/{returnId}/inspection`, BE thu hồi toàn bộ credential ACTIVE của Rental trong cùng transaction:

```text
Credential ACTIVE → REVOKED
revokedAt = now
revokedBy = Staff actor
```

Có thể thu hồi thủ công từng credential qua `POST /api/staff/access-credentials/{credentialId}/revoke` với body `{ "reason": "Thu hồi trong nghiệm thu trả kho" }`. Request chỉ Staff được phép gọi. Gọi lại credential đã `REVOKED` sẽ trả trạng thái hiện tại.

Thông tin `returnedKey`, `returnedCard`, `returnedLock` trong `ReturnInspectionRequest` tiếp tục ghi nhận đối chiếu vật lý. `revokedAt` là bằng chứng vô hiệu hóa quyền truy cập trên hệ thống.

## 8. Manager credential history

`GET /api/manager/access-credentials?facilityId={uuid}&q={text}&status=ACTIVE|REVOKED&page=0&pageSize=20`

Manager chỉ nhận projection lịch sử gồm loại, reference, `issuedAt`, `issuedBy`, `revokedAt`, `revokedBy`, reservation, customer và unit. API không trả secret thô và không có thao tác issue/revoke cho Manager.

## 9. No-show và reject

- `POST /api/staff/check-ins/{checkInId}/no-show`: chỉ khi lịch đã đến hạn. Giữ assignment/unit để có thể lên lịch lại.
- `POST /api/staff/check-ins/{checkInId}/reject`: yêu cầu bằng chứng. Hủy assignment, giải phóng unit hoặc chuyển maintenance theo disposition. Không tạo Rental.
- Nếu reservation bị hủy trước handover, flow cancelled-reservation unit release xử lý assignment/unit. Check-in completed vẫn là blocker.

## Audit và thông báo

Audit events tối thiểu: `CHECKIN_SCHEDULED`, `ACCESS_CREDENTIAL_ISSUED`, `CHECKIN_HANDOVER_COMPLETED`, `ACCESS_CREDENTIAL_REVOKED`, `CHECKIN_NO_SHOW`, `CHECKIN_REJECTED`. Audit ghi actor, entity, before/after state và correlation ID.

Customer nhận thông báo khi lịch được xác nhận, handover chờ receipt, no-show hoặc reject. Việc tạo Rental ACTIVE chỉ xảy ra sau customer receipt confirmation ở module kế tiếp.
