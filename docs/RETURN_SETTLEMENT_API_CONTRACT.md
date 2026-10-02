# Return & Settlement API Contract

Tài liệu đặc tả nghiệp vụ và API cho quy trình **Trả kho (Return) & Quyết toán tài chính (Settlement)** thuộc hệ thống StorageHub.

```text
Active Rental
  → Customer gửi yêu cầu trả kho (status: requested/scheduled, rental: return_requested)
  → Staff nghiệm thu hiện trạng & lập biên bản (status: awaiting_customer_confirmation, rental: return_inspection)
  → Khách hàng xem biên bản & quyết toán:
      ├── Đồng ý (accepted):
      │     ├── netRefundAmount > 0 → status: refund_pending → Staff hoàn cọc → status: completed
      │     ├── amountDueFromCustomer > 0 → status: payment_due → Khách thanh toán → status: completed
      │     └── Cọc = Khấu trừ (0 VND) → status: completed
      └── Khiếu nại (disputed):
            → status: disputed → Manager xem xét, điều chỉnh phí & tái quyết toán
            → Chuyển sang payment_due / refund_pending / completed
  → Kết thúc hợp đồng (Rental: completed, StorageUnit: available hoặc maintenance)
```

---

## 1. Công thức Quyết toán (Settlement Formula)

Bám sát chuẩn tính toán từ Frontend và quy định tài chính:

- **Tổng khấu trừ (totalDeductions)**:
  $$\text{totalDeductions} = \text{damageFee} + \text{cleaningFee} + \text{lostItemFee} + \text{overdueFee} + \text{outstandingFee}$$
- **Tiền bảo đảm (depositAmount)**: Tiền cọc an ninh đã đóng khi nhận kho (mặc định bằng 1 tháng tiền thuê gốc).
- **Tiền hoàn cọc thực nhận (netRefundAmount)**:
  $$\text{netRefundAmount} = \max(0, \text{depositAmount} - \text{totalDeductions})$$
- **Khoản phát sinh khách phải đóng thêm (amountDueFromCustomer)**:
  $$\text{amountDueFromCustomer} = \max(0, \text{totalDeductions} - \text{depositAmount})$$

---

## 2. Bảng phân quyền & Facility Scope

| Thao tác | Vai trò | Permission | Facility Scope |
|---|---|---|---|
| Khách yêu cầu trả kho | `CUSTOMER` | N/A (User sở hữu Rental) | N/A |
| Khách xác nhận/khiếu nại | `CUSTOMER` | N/A (User sở hữu ReturnCase) | N/A |
| Khách nộp phí quyết toán | `CUSTOMER` | N/A (User sở hữu ReturnCase) | N/A |
| Staff lập biên bản nghiệm thu | `STAFF`, `MANAGER`, `ADMIN` | `PROCESS_RETURNS` | `OPERATE` |
| Staff hoàn cọc cho khách | `STAFF`, `MANAGER`, `ADMIN` | `PROCESS_RETURNS` | `OPERATE` |
| Manager xử lý khiếu nại | `MANAGER`, `ADMIN` | `MANAGE_RENTALS` | `MANAGE` |
| Xem danh sách / chi tiết | `STAFF`, `MANAGER`, `ADMIN` | `VIEW_RETURNS` | `READ` |

---

## 3. Danh sách Endpoints

### 3.1. Dành cho Khách hàng (Customer)

#### `POST /api/customer/rentals/{rentalId}/return-request`
Yêu cầu trả kho cho hợp đồng đang hoạt động.
- **Request Body**:
  ```json
  {
    "scheduledDate": "2026-10-15",
    "notes": "Chuyển văn phòng mới, không còn nhu cầu sử dụng kho."
  }
  ```
- **Response**: `ApiResponse<ReturnCaseResponse>` (status: `requested`)

#### `GET /api/customer/returns`
Lấy danh sách các yêu cầu trả kho của khách hàng đăng nhập.
- **Query Params**: `page` (default: 0), `pageSize` (default: 20)
- **Response**: `PageResponse<ReturnCaseResponse>`

#### `GET /api/customer/returns/{returnId}`
Xem chi tiết hồ sơ trả kho và biên bản nghiệm thu.
- **Response**: `ApiResponse<ReturnCaseResponse>`

#### `POST /api/customer/returns/{returnId}/confirm`
Khách hàng phản hồi biên bản nghiệm thu và quyết toán cọc.
- **Request Body (Đồng ý)**:
  ```json
  {
    "decision": "accepted",
    "note": "Đồng ý với các khoản khấu trừ."
  }
  ```
- **Request Body (Khiếu nại)**:
  ```json
  {
    "decision": "disputed",
    "note": "Phí vệ sinh 300.000đ không hợp lý vì trước khi bàn giao tôi đã quét dọn sạch."
  }
  ```
- **Response**: `ApiResponse<ReturnCaseResponse>`

#### `POST /api/customer/returns/{returnId}/pay-settlement`
Thanh toán khoản chênh lệch phát sinh khi tổng phí phạt/hư hại vượt quá tiền cọc (`amountDueFromCustomer > 0`).
- **Request Body**:
  ```json
  {
    "paymentMethod": "VNPAY",
    "notes": "Thanh toán phụ phí hư hại gian kho."
  }
  ```

---

### 3.2. Dành cho Nhân viên (Staff)

#### `GET /api/staff/returns`
Danh sách hồ sơ trả kho tại cơ sở phụ trách.
- **Query Params**: `facilityId`, `status`, `q`, `page`, `pageSize`

#### `GET /api/staff/returns/{returnId}`
Chi tiết hồ sơ trả kho.

#### `POST /api/staff/returns/{returnId}/inspection`
Lưu biên bản nghiệm thu hiện trạng và tính toán quyết toán sơ bộ.
- **Request Body**:
  ```json
  {
    "inventoryMatch": "match",
    "damageClassification": "minor_damage",
    "damageFee": 200000,
    "cleaningFee": 100000,
    "lostItemFee": 0,
    "overdueFee": 0,
    "outstandingFee": 0,
    "staffNotes": "Mặt sàn có vết xước nhẹ, tường sạch sẽ.",
    "evidencePhotos": ["https://storagehub.local/uploads/return-101.jpg"],
    "returnedKey": true,
    "returnedCard": true,
    "returnedLock": true,
    "proposedUnitStatus": "available"
  }
  ```
- **Response**: `ApiResponse<ReturnCaseResponse>` (status: `awaiting_customer_confirmation`)

#### `POST /api/staff/returns/{returnId}/complete-refund`
Ghi nhận chuyển tiền hoàn cọc cho khách hàng (khi status là `refund_pending`).
- **Request Body**:
  ```json
  {
    "transactionReference": "REFUND-VCB-883921",
    "notes": "Đã chuyển khoản hoàn tiền vào số tài khoản khách cung cấp."
  }
  ```

---

### 3.3. Dành cho Quản lý (Manager)

#### `GET /api/manager/returns`
Quản lý toàn bộ danh sách trả kho trong phạm vi chi nhánh.

#### `POST /api/manager/returns/{returnId}/review-dispute`
Manager ra quyết định giải quyết khiếu nại, có thể điều chỉnh lại các khoản phí.
- **Request Body**:
  ```json
  {
    "resolutionNote": "Chấp nhận miễn trừ phí vệ sinh sau khi kiểm tra camera. Giảm từ 100k về 0.",
    "damageFee": 200000,
    "cleaningFee": 0,
    "lostItemFee": 0,
    "overdueFee": 0,
    "outstandingFee": 0,
    "proposedUnitStatus": "available"
  }
  ```
- **Response**: `ApiResponse<ReturnCaseResponse>` (tái quyết toán và chuyển sang `refund_pending`, `payment_due`, hoặc `completed`)

---

## 4. Dữ liệu Phản hồi (ReturnCaseResponse)

```json
{
  "data": {
    "id": "c1f7a08b-...",
    "rentalId": "88a10b42-...",
    "facilityId": "f901a1c3-...",
    "facilityName": "StorageHub Thủ Đức",
    "storageUnitId": "e119bc32-...",
    "unitNumber": "TD-U101",
    "customerId": "948005eb-...",
    "customerName": "Nguyễn Văn Khách",
    "customerEmail": "customer@example.com",
    "customerPhone": "0901234567",
    "status": "awaiting_customer_confirmation",
    "requestedAt": "2026-10-02T19:30:00Z",
    "scheduledDate": "2026-10-05",
    "customerNotes": "Trả kho trước hạn",
    "inspectedById": "d820ca91-...",
    "inspectedByName": "Trần Văn Staff",
    "inspectedAt": "2026-10-05T09:15:00Z",
    "inventoryMatch": "match",
    "damageClassification": "minor_damage",
    "inspectionNotes": "Đầy đủ vật dụng",
    "evidencePhotos": ["https://.../photo.jpg"],
    "returnedKey": true,
    "returnedCard": true,
    "returnedLock": true,
    "proposedUnitStatus": "available",
    "depositAmount": 2000000,
    "damageFee": 200000,
    "cleaningFee": 100000,
    "lostItemFee": 0,
    "overdueFee": 0,
    "outstandingFee": 0,
    "totalDeductions": 300000,
    "netRefundAmount": 1700000,
    "amountDueFromCustomer": 0,
    "customerConfirmed": false,
    "settlementPaymentId": null,
    "completedAt": null
  },
  "correlationId": "..."
}
```
