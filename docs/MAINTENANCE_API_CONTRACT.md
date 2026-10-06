# StorageHub - Unit Maintenance & Staff Task Assignment API Contract

Tài liệu đặc tả API phân hệ **Bảo trì gian kho & Phân công Staff (Maintenance & Staff Task Assignment)** thuộc phạm vi phụ trách của **Phương**.

---

## 1. Phân quyền & Vai trò (Role & Permissions)

- **Manager (`FACILITY_MANAGER`, `MANAGER`)**:
  - Quyền `MANAGE_INVENTORY`: Chuyển trạng thái gian kho (`available` <-> `maintenance`), khởi tạo công việc bảo trì gian kho.
  - Quyền `MANAGE_STAFF_TASKS`: Phân công nhiệm vụ (`assign`), hủy nhiệm vụ (`cancel`).
  - Quyền `VIEW_UNITS`: Xem danh sách nhiệm vụ bảo trì và lịch sử bảo trì gian kho theo facility scope.
- **Staff (`FACILITY_STAFF`, `STAFF`)**:
  - Quyền `VIEW_UNITS`: Xem các nhiệm vụ bảo trì của cơ sở hoặc được phân công.
  - Nhận nhiệm vụ, bắt đầu tiến hành bảo trì (`startTask` -> chuyển sang `in_progress`).
  - Hoàn tất nghiệm thu (`completeTask` -> ghi nhận báo cáo kết quả, ảnh bằng chứng, tự động giải phóng gian kho về `available`).

---

## 2. API Endpoints

### 2.1 Quản lý gian kho & Nhiệm vụ bảo trì (Manager)

#### 1. Cập nhật trạng thái gian kho (`available` / `maintenance`)
- **Endpoint**: `PATCH /api/manager/storage-units/{unitId}/status`
- **Quyền**: `MANAGE_INVENTORY` (kèm Facility Scope)
- **Request Body**:
```json
{
  "status": "maintenance",
  "reason": "Cửa gian kho bị kẹt ray trượt",
  "createMaintenanceTask": true,
  "assignedStaffId": "d3b07384-d113-4091-bf96-51d02c892801",
  "priority": "high",
  "damageClassification": "minor_damage",
  "dueAt": "2026-10-05"
}
```
- **Xử lý**:
  - Không cho phép chuyển gian đang `occupied` (đang có khách thuê) sang `maintenance` nếu chưa hoàn tất thủ tục trả kho.
  - Nếu chuyển sang `maintenance` và `createMaintenanceTask=true`: Hệ thống tự động tạo một nhiệm vụ `MaintenanceTask` trạng thái `open`, gửi thông báo đến Staff được phân công.
  - Nếu chuyển sang `available`: Tự động nghiệm thu các công việc bảo trì đang mở (`open`, `in_progress`) của gian này và giải phóng gian kho cho khách đặt tiếp theo.

#### 2. Xem lịch sử bảo trì của gian kho
- **Endpoint**: `GET /api/manager/storage-units/{unitId}/maintenance-history`
- **Quyền**: `VIEW_UNITS`
- **Response**: Mảng các `MaintenanceTaskResponse` sắp xếp theo thời gian mới nhất.

#### 3. Danh sách công việc bảo trì
- **Endpoint**: `GET /api/manager/maintenance-tasks`
- **Tham số lọc**:
  - `facilityId`: UUID cơ sở
  - `storageUnitId`: UUID gian kho
  - `status`: `open` | `in_progress` | `completed` | `cancelled`
  - `priority`: `low` | `medium` | `high`
  - `assignedStaffId`: UUID nhân viên được giao
  - `q`: Từ khóa tìm kiếm theo mã gian hoặc tiêu đề
  - `page`: Trang (mặc định 0)
  - `pageSize`: Kích thước trang (mặc định 20)

#### 4. Tạo nhiệm vụ bảo trì thủ công
- **Endpoint**: `POST /api/manager/maintenance-tasks`
- **Request Body**:
```json
{
  "storageUnitId": "d3b07384-d113-4091-bf96-51d02c892801",
  "title": "Bảo dưỡng hệ thống thông gió định kỳ",
  "reason": "Vệ sinh lưới lọc gió và kiểm tra đèn chiếu sáng",
  "priority": "medium",
  "damageClassification": "no_damage",
  "assignedStaffId": "d3b07384-d113-4091-bf96-51d02c892802",
  "dueAt": "2026-10-10",
  "returnCaseId": null
}
```

#### 5. Phân công / Đổi nhân viên xử lý
- **Endpoint**: `PATCH /api/manager/maintenance-tasks/{taskId}/assign`
- **Request Body**:
```json
{
  "assignedStaffId": "d3b07384-d113-4091-bf96-51d02c892802"
}
```

#### 6. Hủy nhiệm vụ bảo trì
- **Endpoint**: `POST /api/manager/maintenance-tasks/{taskId}/cancel`
- **Request Body**:
```json
{
  "reason": "Gian kho đã được chuyển đổi mục đích khác hoặc thông tin nhầm lẫn"
}
```

---

### 2.2 Nhân viên thực hiện bảo trì (Staff)

#### 1. Xem danh sách nhiệm vụ được giao / thuộc cơ sở
- **Endpoint**: `GET /api/staff/maintenance-tasks`
- Hỗ trợ các query params: `facilityId`, `status`, `priority`, `assignedStaffId`, `q`, `page`, `pageSize`.

#### 2. Xem chi tiết nhiệm vụ
- **Endpoint**: `GET /api/staff/maintenance-tasks/{taskId}`

#### 3. Bắt đầu tiến hành bảo trì
- **Endpoint**: `POST /api/staff/maintenance-tasks/{taskId}/start`
- **Xử lý**: Chuyển trạng thái task sang `in_progress`, tự động ghi nhận `startedAt`, nếu task chưa có người phụ trách thì gán luôn cho staff hiện tại.

#### 4. Hoàn thành bảo trì & Báo cáo kết quả
- **Endpoint**: `POST /api/staff/maintenance-tasks/{taskId}/complete`
- **Request Body**:
```json
{
  "resultReport": "Đã thay gioăng cao su và bôi trơn bản lề cửa. Cửa đóng mở êm ái.",
  "evidencePhotos": [
    "https://storage.storagehub.vn/evidence/maintenance_after_1.jpg",
    "https://storage.storagehub.vn/evidence/maintenance_after_2.jpg"
  ],
  "makeUnitAvailable": true
}
```
- **Xử lý**:
  - Cập nhật trạng thái task thành `completed`, ghi nhận `completedAt`, lưu báo cáo kết quả và hình ảnh.
  - Nếu `makeUnitAvailable = true` (mặc định), tự động chuyển `storage_unit.status` về `available` để sẵn sàng cho khách hàng thuê mới.

---

## 3. Data Models

### 3.1 `MaintenanceTaskResponse`
```json
{
  "id": "c1f7a242-8921-4f9e-8c3b-7411b3da6811",
  "facilityId": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
  "facilityName": "StorageHub Quận 1",
  "storageUnitId": "e1f2a3b4-c5d6-7890-abcd-112233445566",
  "unitCode": "Q1-A-102",
  "returnCaseId": null,
  "title": "Bảo dưỡng khóa điện tử gian Q1-A-102",
  "reason": "Pin yếu và khóa chập chờn",
  "priority": "high",
  "damageClassification": "minor_damage",
  "reportedById": "a1b2c3d4-...",
  "reportedByName": "Nguyen Van Manager",
  "assignedStaffId": "s1s2s3s4-...",
  "assignedStaffName": "Tran Van Staff",
  "status": "in_progress",
  "dueAt": "2026-10-06",
  "startedAt": "2026-10-02T14:30:00Z",
  "completedAt": null,
  "resultReport": null,
  "evidencePhotos": [],
  "createdAt": "2026-10-02T13:00:00Z",
  "updatedAt": "2026-10-02T14:30:00Z"
}
```

### 3.2 Enum Types
- `TaskPriority`: `low`, `medium`, `high`
- `MaintenanceTaskStatus`: `open`, `in_progress`, `completed`, `cancelled`
- `DamageClassification`: `no_damage`, `minor_damage`, `major_damage`, `abandoned_goods`
- `StorageUnitStatus`: `available`, `reserved`, `occupied`, `maintenance`, `inactive`
