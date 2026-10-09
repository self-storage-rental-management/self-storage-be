# StorageHub - Business Configuration & Reporting API Contract

Tài liệu đặc tả API phân hệ **Cấu hình chính sách & Báo cáo thống kê (Configuration & Reporting)** thuộc phạm vi phụ trách của **Phương**.

---

## 1. Phân quyền & Vai trò (Role & Permissions)

- **Business Operations Manager (`BUSINESS`)**:
  - `VIEW_POLICIES`: Xem cấu hình vận hành và chính sách giá thuê.
  - `MANAGE_POLICIES`: Cập nhật cấu hình vận hành (`gracePeriodDays`, `lateFeeAmount`, `defaultDepositRatio`, `holdExpiryHours`, `dimDivisor`, `maintenanceMode`, `bannerNotice`) và quản lý các gói ưu đãi thuê (`RentalPackagePolicy`).
  - `VIEW_REPORTS`: Xem báo cáo doanh thu hệ thống (`/api/business/reports/revenue`) và hiệu suất mạng lưới cơ sở (`/api/business/reports/performance`).
- **Facility Manager (`FACILITY_MANAGER`, `MANAGER`)**:
  - `policies:read`: Đọc chính sách chung để áp dụng tại cơ sở. Không được `policies:update`, kể cả khi tài khoản còn quyền cũ trong DB/session. Các lệnh sửa cấu hình và gói thuê kiểm tra role `BUSINESS` cùng quyền `policies:update` ở service.
  - `VIEW_UNITS` / `VIEW_REPORTS`: Xem báo cáo tổng hợp cơ sở thuộc phạm vi quản lý (`/api/manager/reports/summary`) và nhật ký hoạt động cơ sở (`/api/manager/reports/activities`).

Quyết định quyền policy được chủ dự án duyệt ngày 09/10/2026: BO sở hữu policy, Manager chỉ đọc. Các tên enum `VIEW_POLICIES` / `MANAGE_POLICIES` tương ứng mã API `policies:read` / `policies:update`. Admin quản lý cấp quyền, không trực tiếp sửa policy. `RoleDataInitializer` nâng phiên bản quyền từ 3 lên 4, chỉ điều chỉnh quyền policy của Manager/BO và giữ các quyền tùy chỉnh khác; `AdminRoleService` bảo vệ ranh giới này khi sửa bảng quyền. Chưa chạy nâng phiên bản trên DB thật trong lượt sửa này vì schema test vẫn chưa qua `validate`.

---

## 2. API Endpoints

### 2.1 Cấu hình vận hành & Chính sách thuê (Configuration)

#### 1. Lấy cấu hình vận hành hiện tại
- **Endpoint**: `GET /api/business/config`
- **Quyền**: `VIEW_POLICIES`
- **Response**:
```json
{
  "gracePeriodDays": 3,
  "lateFeeAmount": 650000.00,
  "defaultDepositRatio": 0.20,
  "holdExpiryHours": 2.0,
  "dimDivisor": 5000,
  "maintenanceMode": false,
  "bannerNotice": "Lịch kiểm tra PCCC vào Chủ Nhật 9:00 - 11:00.",
  "autoInvoiceDays": 7,
  "autoProrate": true
}
```

#### 2. Cập nhật cấu hình vận hành
- **Endpoint**: `PUT /api/business/config`
- **Quyền**: `MANAGE_POLICIES`
- **Request Body**:
```json
{
  "gracePeriodDays": 5,
  "lateFeeAmount": 700000.00,
  "defaultDepositRatio": 0.25,
  "holdExpiryHours": 3.0,
  "dimDivisor": 5000,
  "maintenanceMode": false,
  "bannerNotice": "Thông báo bảo trì hệ thống định kỳ",
  "autoInvoiceDays": 7,
  "autoProrate": true
}
```

#### 3. Danh sách chính sách gói thuê (Rental Package Policies)
- **Endpoint**: `GET /api/business/policies/packages`
- **Query Params**:
  - `facilityId`: UUID cơ sở (tùy chọn)
  - `activeOnly`: `true` | `false` (tùy chọn)
- **Response**: Mảng danh sách các gói ưu đãi theo thời hạn thuê.

#### 4. Tạo gói chính sách thuê mới
- **Endpoint**: `POST /api/business/policies/packages`
- **Quyền**: `MANAGE_POLICIES`
- **Request Body**:
```json
{
  "facilityId": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
  "code": "PKG-12M",
  "name": "Gói thuê 12 tháng giảm 15%",
  "rentalMonths": 12,
  "discountRate": 0.15,
  "policyVersion": "v1.0",
  "active": true,
  "effectiveFrom": "2026-01-01",
  "effectiveTo": "2026-12-31"
}
```

#### 5. Cập nhật gói chính sách thuê
- **Endpoint**: `PUT /api/business/policies/packages/{id}`
- **Quyền**: `MANAGE_POLICIES`

#### 6. Hủy kích hoạt gói chính sách thuê
- **Endpoint**: `DELETE /api/business/policies/packages/{id}`
- **Quyền**: `MANAGE_POLICIES`

---

### 2.2 Báo cáo thống kê (Reporting)

#### 1. Báo cáo tổng hợp cơ sở (Manager Facility KPIs)
- **Endpoint**: `GET /api/manager/reports/summary`
- **Query Params**: `facilityId` (tùy chọn, mặc định lấy cơ sở trong scope của Manager)
- **Quyền**: `VIEW_UNITS` / `VIEW_REPORTS`
- **Response**:
```json
{
  "facilityId": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
  "facilityName": "StorageHub Quận 1",
  "totalUnits": 50,
  "availableUnits": 15,
  "occupiedUnits": 30,
  "maintenanceUnits": 3,
  "reservedUnits": 2,
  "occupancyRate": 60.0,
  "monthlyRecurringRevenue": 65000000.00,
  "collectedRevenue": 142000000.00,
  "overdueRentalsCount": 2,
  "monthCheckinsCount": 8,
  "openReturnsCount": 3,
  "openMaintenanceTasksCount": 3,
  "unitStatusBreakdown": [
    { "status": "available", "name": "Sẵn sàng", "count": 15, "percentage": 30.0 },
    { "status": "occupied", "name": "Đang thuê", "count": 30, "percentage": 60.0 },
    { "status": "maintenance", "name": "Bảo trì", "count": 3, "percentage": 6.0 }
  ],
  "unitTypeBreakdown": [
    { "typeCode": "small", "typeName": "Kho nhỏ 5-10m²", "totalUnits": 20, "occupiedUnits": 16, "occupancyRate": 80.0 },
    { "typeCode": "medium", "typeName": "Kho vừa 10-25m²", "totalUnits": 30, "occupiedUnits": 14, "occupancyRate": 46.7 }
  ]
}
```

#### 2. Nhật ký hoạt động cơ sở (Manager Facility Activities)
- **Endpoint**: `GET /api/manager/reports/activities`
- **Query Params**:
  - `facilityId`: UUID cơ sở
  - `entityType`: `storage_unit` | `rental` | `checkin` | `return` | `maintenance_task`
  - `search`: Từ khóa tìm kiếm hành động hoặc ghi chú
  - `page`: Trang (0-indexed)
  - `pageSize`: Kích thước trang (mặc định 20)

#### 3. Báo cáo doanh thu toàn hệ thống (Business Revenue Report)
- **Endpoint**: `GET /api/business/reports/revenue`
- **Query Params**: `months` (số tháng thống kê, mặc định 6)
- **Quyền**: `VIEW_REPORTS`
- **Response**:
```json
{
  "totalRevenueCollected": 450000000.00,
  "totalRefunds": 0.00,
  "netRevenue": 450000000.00,
  "monthlyBreakdown": [
    { "month": "2026-05", "revenue": 68000000.00, "transactionsCount": 24 },
    { "month": "2026-06", "revenue": 75000000.00, "transactionsCount": 28 }
  ],
  "facilityBreakdown": [
    { "facilityId": "...", "facilityName": "Kho Quận 1", "revenue": 240000000.00, "occupancyRate": 82.5, "activeRentalsCount": 35 }
  ],
  "paymentPurposeBreakdown": [
    { "purpose": "MONTHLY_RENT", "amount": 350000000.00, "count": 95 },
    { "purpose": "RESERVATION_DEPOSIT", "amount": 100000000.00, "count": 30 }
  ]
}
```

#### 4. Báo cáo so sánh hiệu suất cơ sở (Business Performance Report)
- **Endpoint**: `GET /api/business/reports/performance`
- **Quyền**: `VIEW_REPORTS`
- **Response**:
```json
{
  "totalFacilities": 4,
  "totalCapacityUnits": 200,
  "totalOccupiedUnits": 165,
  "systemOccupancyRate": 82.5,
  "facilityComparisons": [
    {
      "facilityId": "...",
      "facilityName": "Kho Quận 1",
      "city": "Hồ Chí Minh",
      "totalUnits": 50,
      "occupiedUnits": 42,
      "occupancyRate": 84.0,
      "activeRentals": 42,
      "openReturns": 2,
      "openMaintenance": 3
    }
  ]
}
```
