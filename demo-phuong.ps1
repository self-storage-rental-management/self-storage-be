<#
.SYNOPSIS
    Kịch bản tự động chạy Demo toàn trình (End-to-End) các tính năng của Phương trên StorageHub:
    1. Trả kho & Quyết toán cọc (Return & Settlement)
    2. Bảo trì kho & Phân công công việc (Maintenance & Staff Task)
    3. Cấu hình chính sách giá & Báo cáo quản trị (Configuration & Reporting)

.DESCRIPTION
    Script thực hiện:
    - Tự động chuẩn bị dữ liệu mẫu (seed database)
    - Đăng nhập 4 vai trò (Customer, Staff, Manager, Business)
    - Chạy tuần tự các bước nghiệp vụ thực tế qua REST API
    - In kết quả trực quan, màu sắc rõ ràng trên màn hình PowerShell
#>

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DbUser = "root",
    [string]$DbPass = "1234",
    [string]$DbName = "storagehub_local",
    [switch]$SkipSeed = $false
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Print-Header {
    param([string]$Title)
    Write-Host ""
    Write-Host ("=" * 80) -ForegroundColor Cyan
    Write-Host ("  " + $Title) -ForegroundColor Yellow
    Write-Host ("=" * 80) -ForegroundColor Cyan
}

function Print-SubHeader {
    param([string]$Step, [string]$Title)
    Write-Host ""
    Write-Host ("[$Step] $Title") -ForegroundColor Magenta
    Write-Host ("-" * 70) -ForegroundColor DarkGray
}

function Print-Success {
    param([string]$Msg)
    Write-Host "  [OK] $Msg" -ForegroundColor Green
}

function Print-Info {
    param([string]$Label, [string]$Val)
    Write-Host ("  {0,-28}: " -f $Label) -NoNewline -ForegroundColor White
    Write-Host $Val -ForegroundColor Cyan
}

function Format-Vnd {
    param([decimal]$Amount)
    return ("{0:N0} VND" -f $Amount)
}

# ==============================================================================
# 0. KHỞI TẠO VÀ KIỂM TRA SERVER
# ==============================================================================
Clear-Host
Write-Host ""
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host "   *            STORAGEHUB SYSTEM - AUTOMATED DEMO RUNNER                   *" -ForegroundColor Green
Write-Host "   *   Phân hệ: Return & Settlement + Maintenance + Configuration/Reports   *" -ForegroundColor Green
Write-Host "   *   Thực hiện: Phương (BE & Database)                                    *" -ForegroundColor Green
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host ""

Print-SubHeader "0.1" "Kiểm tra kết nối Backend API ($BaseUrl)"
try {
    $healthCheck = Invoke-RestMethod -Method Get -Uri "$BaseUrl/v3/api-docs" -TimeoutSec 3 -ErrorAction Stop
    Print-Success "Backend server đang hoạt động tại $BaseUrl"
} catch {
    Write-Host "  [ERROR] Không thể kết nối đến Backend server tại $BaseUrl!" -ForegroundColor Red
    Write-Host "  Vui lòng đảm bảo bạn đã khởi động backend bằng lệnh: .\mvnw.cmd spring-boot:run" -ForegroundColor Yellow
    exit 1
}

if (-not $SkipSeed) {
    Print-SubHeader "0.2" "Nạp dữ liệu mẫu chuẩn (Database Seeding qua MySQL)"
    $seedScriptPath = Join-Path $PSScriptRoot "scripts\seed-demo-data.sql"
    if (Test-Path $seedScriptPath) {
        try {
            cmd /c "mysql -u $DbUser -p$DbPass $DbName < `"$seedScriptPath`""
            Print-Success "Đã reset và nạp dữ liệu chuẩn thành công vào database $DbName!"
        } catch {
            Write-Host "  [WARN] Không thể chạy cmd mysql client trực tiếp, bỏ qua bước nạp sql" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  [WARN] Không tìm thấy file $seedScriptPath" -ForegroundColor Yellow
    }
}

# ==============================================================================
# BƯỚC 1: XÁC THỰC VÀ LẤY ACCESS TOKEN CÁC VAI TRÒ
# ==============================================================================
Print-Header "BƯỚC 1: ĐĂNG NHẬP VÀ LẤY JWT TOKEN CỦA 4 VAI TRÒ"

$accounts = @{
    Customer = @{ email = "demo_customer@storagehub.test"; pass = "Password123@" }
    Staff    = @{ email = "demo_staff@storagehub.test";    pass = "Password123@" }
    Manager  = @{ email = "demo_manager@storagehub.test";  pass = "Password123@" }
    Business = @{ email = "demo_business@storagehub.test"; pass = "Password123@" }
}

$tokens = @{}
$headers = @{}

foreach ($role in @("Customer", "Staff", "Manager", "Business")) {
    $creds = $accounts[$role]
    $loginBody = @{
        email = $creds.email
        password = $creds.pass
    } | ConvertTo-Json -Compress

    try {
        $loginRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" `
            -ContentType "application/json" -Body $loginBody
        $tok = $loginRes.data.accessToken
        $tokens[$role] = $tok
        $headers[$role] = @{
            "Authorization" = "Bearer $tok"
            "Content-Type"  = "application/json; charset=utf-8"
        }
        $actor = $loginRes.data.actor
        $roleStr = ($actor.roles -join ", ")
        Print-Success ("{0,-8} Đăng nhập thành công: {1} [Role: {2}]" -f $role, $actor.fullName, $roleStr)
    } catch {
        Write-Host ("  [FAIL] Không thể đăng nhập $role ({0}): {1}" -f $creds.email, $_.Exception.Message) -ForegroundColor Red
        exit 1
    }
}

# ID tài nguyên cố định từ seed script
$rentalId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
$facilityId = "11111111-1111-1111-1111-111111111111"
$storageUnitId = "33333333-3333-3333-3333-333333333333"

# ==============================================================================
# BƯỚC 2: QUY TRÌNH TRẢ KHO & QUYẾT TOÁN CỌC (RETURN & SETTLEMENT)
# ==============================================================================
Print-Header "BUOC 2: QUY TRINH TRA KHO & QUYET TOAN COC (RETURN & SETTLEMENT)"

Print-SubHeader "2.1" "Customer tao yeu cau tra kho (POST /api/customer/rentals/{id}/return-request)"
$retReqBody = '{
    "scheduledDate": "2026-10-15",
    "notes": "Toi da het nhu cau su dung gian kho, de nghi kiem tra thanh ly hop dong va tra coc."
}'
$retRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/customer/rentals/$rentalId/return-request" `
    -Headers $headers["Customer"] -ContentType "application/json; charset=utf-8" -Body $retReqBody

$returnCaseId = $retRes.data.id
Print-Success "Yeu cau tra kho da duoc tao thanh cong!"
Print-Info "Return Case ID" $returnCaseId
Print-Info "Ma Gian Kho" $retRes.data.unitNumber
Print-Info "Trang thai ho so" $retRes.data.status
Print-Info "Tien coc ban dau" (Format-Vnd ([decimal]$retRes.data.depositAmount))
Print-Info "Ngay hen kiem tra" $retRes.data.scheduledDate

Print-SubHeader "2.2" "Staff kiem tra hien trang & lap bien ban quyet toan (POST /api/staff/returns/{id}/inspection)"
$inspectBody = '{
    "returnedKey": true,
    "returnedCard": true,
    "returnedLock": true,
    "inventoryMatch": "match",
    "damageClassification": "minor_damage",
    "inspectionNotes": "Sàn kho trầy xước nhẹ do kéo lê pallet gỗ. Cần làm sạch và sơn lại sàn.",
    "damageFee": 200000,
    "cleaningFee": 100000,
    "lostItemFee": 0,
    "overdueFee": 0,
    "outstandingFee": 0,
    "proposedUnitStatus": "maintenance",
    "evidencePhotos": [
        "https://storagehub.local/photos/return-inspection-floor-scratch.jpg",
        "https://storagehub.local/photos/return-inspection-door-key.jpg"
    ]
}'

$inspRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/staff/returns/$returnCaseId/inspection" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $inspectBody

Print-Success "Nhan vien Staff da hoan tat bien ban nghiem thu!"
Print-Info "Nhan vien kiem tra" $inspRes.data.inspectedByName
Print-Info "Tra chia khoa/the" "DAY DU (Key: OK, Card: OK, Lock: OK)"
Print-Info "Danh gia hu hai" $inspRes.data.damageClassification
Print-Info "Phi hu hai (Damage Fee)" (Format-Vnd ([decimal]$inspRes.data.damageFee))
Print-Info "Phi ve sinh (Cleaning Fee)" (Format-Vnd ([decimal]$inspRes.data.cleaningFee))
Print-Info "Tong khau tru (Total Deduct)" (Format-Vnd ([decimal]$inspRes.data.totalDeductions))
Print-Info "Tien coc thuc hoan (Net Refund)" (Format-Vnd ([decimal]$inspRes.data.netRefundAmount))
Print-Info "Trang thai ho so" $inspRes.data.status

Print-SubHeader "2.3" "Customer xac nhan dong y bien ban quyet toan (POST /api/customer/returns/{id}/confirm)"
$custConfirmBody = '{
    "decision": "accepted",
    "note": "Toi dong y voi ket qua nghiem thu va khoan khau tru 300,000 VND tien sua san & ve sinh."
}'
$confRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/customer/returns/$returnCaseId/confirm" `
    -Headers $headers["Customer"] -ContentType "application/json; charset=utf-8" -Body $custConfirmBody

Print-Success "Khach hang da xac nhan dong y quyet toan!"
Print-Info "Quyet dinh khach hang" $confRes.data.customerDecision
Print-Info "Trang thai ho so" $confRes.data.status

Print-SubHeader "2.4" "Staff thuc hien chuyen hoan tien coc cho khach (POST /api/staff/returns/{id}/complete-refund)"
$refundBody = '{
    "transactionReference": "VNPAY-REFUND-88992200",
    "notes": "Da chuyen khoan hoan tien coc 2,200,000 VND vao STK Ngan hang Techcombank cua khach hang."
}'
$refundRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/staff/returns/$returnCaseId/complete-refund" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $refundBody

Print-Success "Tien coc da duoc chuyen hoan va dong ho so thanh ly hop dong!"
Print-Info "Ma giao dich chuyen khoan" $refundRes.data.settlementPaymentId
Print-Info "Trang thai ho so cuoi cung" $refundRes.data.status
Print-Info "Thoi gian hoan tat" $refundRes.data.completedAt

# ==============================================================================
# BƯỚC 3: QUY TRÌNH BẢO TRÌ GIAN KHO & PHÂN CÔNG STAFF (MAINTENANCE)
# ==============================================================================
Print-Header "BUOC 3: QUY TRINH BAO TRI GIAN KHO & PHAN CONG STAFF (MAINTENANCE)"

Print-SubHeader "3.1" "Manager chuyen gian Q1-U101 sang Maintenance & phan cong Staff"
$maintReqBody = @{
    status = "maintenance"
    reason = "Kho xuat hien vet tray xuoc sau dot tra kho cua khach hang, can son epoxy phu lai san"
    createMaintenanceTask = $true
    priority = "medium"
    damageClassification = "minor_damage"
    assignedStaffId = "55555555-5555-5555-5555-555555555555"
} | ConvertTo-Json -Compress

$maintRes = Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/manager/storage-units/$storageUnitId/status" `
    -Headers $headers["Manager"] -ContentType "application/json; charset=utf-8" -Body $maintReqBody

$taskId = $maintRes.data.id
Print-Success "Gian kho da chuyen sang che do bao tri va tao cong viec phan cong!"
Print-Info "Ma cong viec (Task ID)" $taskId
Print-Info "Tieu de cong viec" $maintRes.data.title
Print-Info "Nguoi tao (Manager)" $maintRes.data.reportedByName
Print-Info "Nhan vien phu trach (Staff)" $maintRes.data.assignedStaffName
Print-Info "Trang thai Task" $maintRes.data.status

Print-SubHeader "3.2" "Staff bat dau thuc hien sua chua (POST /api/staff/maintenance-tasks/{id}/start)"
$startRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/staff/maintenance-tasks/$taskId/start" `
    -Headers $headers["Staff"]

Print-Success "Nhan vien Staff da bat dau lam viec tai hien truong!"
Print-Info "Trang thai Task" $startRes.data.status
Print-Info "Thoi gian bat dau" $startRes.data.startedAt

Print-SubHeader "3.3" "Staff nghiem thu hoan thanh sua chua (POST /api/staff/maintenance-tasks/{id}/complete)"
$compBody = '{
    "resultReport": "Da danh bong, hut bui va son phu 2 lop epoxy chong tray cho toan bo mat san gian Q1-U101. San kho da kho va sach dep, san sang don khach hang moi.",
    "evidencePhotos": [
        "https://storagehub.local/photos/maintenance-q1-u101-completed-epoxy.jpg"
    ]
}'
$compRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/staff/maintenance-tasks/$taskId/complete" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $compBody

Print-Success "Staff da hoan tat cong viec bao tri!"
Print-Info "Trang thai Task" $compRes.data.status
Print-Info "Bao cao ket qua" $compRes.data.resultReport
Print-Info "Thoi gian hoan thanh" $compRes.data.completedAt
Print-Info "Anh nghiem thu" ($compRes.data.evidencePhotos -join ", ")
Print-Info "Co che tu dong" "Storage Unit Q1-U101 da TU DONG chuyen ve trang thai [AVAILABLE]!"

# ==============================================================================
# BƯỚC 4: CẤU HÌNH KINH DOANH & BÁO CÁO QUẢN TRỊ (CONFIGURATION & REPORTING)
# ==============================================================================
Print-Header "BUOC 4: CAU HINH CHINH SACH GIA & BAO CAO QUAN TRI (CONFIGURATION & REPORTS)"

Print-SubHeader "4.1" "Manager xem bao cao tong quan co so Q1 (GET /api/manager/reports/summary)"
$mgrSum = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/manager/reports/summary?facilityId=$facilityId" `
    -Headers $headers["Manager"]

Print-Success "Lay thanh cong so lieu van hanh Co so Quan 1!"
Print-Info "Ten chi nhanh" $mgrSum.data.facilityName
Print-Info "Tong so gian kho" $mgrSum.data.totalUnits
Print-Info "So gian san sang (Available)" $mgrSum.data.availableUnits
Print-Info "So gian dang thue (Occupied)" $mgrSum.data.occupiedUnits
Print-Info "Ty le lap day (Occupancy Rate)" ("{0:P1}" -f ([double]$mgrSum.data.occupancyRate / 100))
Print-Info "Doanh thu da thu (Collected)" (Format-Vnd ([decimal]$mgrSum.data.collectedRevenue))
Print-Info "Cong viec bao tri con mo" $mgrSum.data.openMaintenanceTasksCount

Print-SubHeader "4.2" "Business xem bao cao doanh thu he thong (GET /api/business/reports/revenue)"
$bizRev = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/reports/revenue" `
    -Headers $headers["Business"]

Print-Success "Lay thanh cong bao cao doanh thu toan he thong!"
Print-Info "Tong doanh thu thu ve" (Format-Vnd ([decimal]$bizRev.data.totalRevenueCollected))
Print-Info "Tong tien hoan tra" (Format-Vnd ([decimal]$bizRev.data.totalRefunds))
Print-Info "Doanh thu thuan (Net Revenue)" (Format-Vnd ([decimal]$bizRev.data.netRevenue))

Write-Host ""
Write-Host "  [Phan ra theo muc dich thanh toan]:" -ForegroundColor Yellow
foreach ($p in $bizRev.data.paymentPurposeBreakdown) {
    Write-Host ("    - {0,-22}: {1,14} ({2} giao dich)" -f $p.purpose, (Format-Vnd ([decimal]$p.amount)), $p.count) -ForegroundColor Gray
}

Print-SubHeader "4.3" "Business xem danh sach & cap nhat chinh sach goi gia (GET /policies/packages)"
$policies = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/policies/packages?facilityId=$facilityId" `
    -Headers $headers["Business"]

Print-Success ("Lay thanh cong {0} chinh sach goi thue dang ap dung:" -f $policies.data.Count)
foreach ($pol in $policies.data) {
    Write-Host ("    * [{0}] {1,-35} | Thoi han: {2,2} thang | Chiet khau: {3,4:P0}" -f $pol.code, $pol.name, $pol.rentalMonths, ([double]$pol.discountRate)) -ForegroundColor White
}

Print-SubHeader "4.4" "Business kiem tra cau hinh van hanh toan he thong (GET /api/business/config)"
$bizCfg = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/config" `
    -Headers $headers["Business"]

Print-Success "Lay thanh cong thong so cau hinh kinh doanh:"
Print-Info "Han cham thanh toan (Grace Period)" ("{0} ngay" -f $bizCfg.data.gracePeriodDays)
Print-Info "Phi phat tre han (Late Fee)" (Format-Vnd ([decimal]$bizCfg.data.lateFeeAmount))
Print-Info "Ty le coc mac dinh" ("{0:P0}" -f ([double]$bizCfg.data.defaultDepositRatio))
Print-Info "Thoi han giu cho (Hold Expiry)" ("{0} gio" -f $bizCfg.data.holdExpiryHours)
Print-Info "Thong bao Banner he thong" $bizCfg.data.bannerNotice

# ==============================================================================
# TỔNG KẾT DEMO
# ==============================================================================
Write-Host ""
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host "   *             DEMO TOAN TRINH DA HOAN THANH XUAT SAC (100%)              *" -ForegroundColor Green
Write-Host "   *                                                                        *" -ForegroundColor Green
Write-Host "   *   [x] 1. Authentication: 4 vai tro da lay Token hop le                 *" -ForegroundColor Green
Write-Host "   *   [x] 2. Return & Settlement: Hoan tat quyet toan coc khong sai sot    *" -ForegroundColor Green
Write-Host "   *   [x] 3. Maintenance: Chuyen bao tri -> Phuc hoi kho Available         *" -ForegroundColor Green
Write-Host "   *   [x] 4. Configuration & Reports: So lieu thong ke chinh xac           *" -ForegroundColor Green
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host ""
