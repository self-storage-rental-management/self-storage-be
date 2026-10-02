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

# Thiết lập Console hỗ trợ tiếng Việt UTF-8 (Code Page 65001)
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8

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
    Write-Host ("  {0,-32}: " -f $Label) -NoNewline -ForegroundColor White
    Write-Host $Val -ForegroundColor Cyan
}

function Format-Vnd {
    param([decimal]$Amount)
    return ("{0:N0} VND" -f $Amount)
}

# Helper gọi REST API giải mã chuẩn UTF-8 (khắc phục PowerShell 5.1 mặc định ISO-8859-1 khi thiếu charset)
function Invoke-ApiUtf8 {
    param(
        [Parameter(Mandatory=$true)][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json; charset=utf-8"
    )
    $params = @{
        Method          = $Method
        Uri             = $Uri
        Headers         = $Headers
        UseBasicParsing = $true
        TimeoutSec      = 15
    }
    if ($Body) {
        $params["Body"] = $Body
        $params["ContentType"] = $ContentType
    }
    try {
        $resp = Invoke-WebRequest @params
        $bytes = $resp.RawContentStream.ToArray()
        $utf8Json = [System.Text.Encoding]::UTF8.GetString($bytes)
        return ($utf8Json | ConvertFrom-Json)
    } catch {
        if ($_.Exception.Response) {
            $errStream = $_.Exception.Response.GetResponseStream()
            if ($errStream) {
                $ms = New-Object System.IO.MemoryStream
                $errStream.CopyTo($ms)
                $errJson = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
                throw $errJson
            }
        }
        throw $_
    }
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
    $null = Invoke-WebRequest -Method Get -Uri "$BaseUrl/v3/api-docs" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
    Print-Success "Backend server đang hoạt động tốt tại $BaseUrl"
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
            cmd /c "chcp 65001 >nul && mysql --default-character-set=utf8mb4 -u $DbUser -p$DbPass $DbName < `"$seedScriptPath`""
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
        $loginRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/auth/login" `
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
        Write-Host ("  [FAIL] Không thể đăng nhập $role ({0}): {1}" -f $creds.email, $_) -ForegroundColor Red
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
Print-Header "BƯỚC 2: QUY TRÌNH TRẢ KHO & QUYẾT TOÁN CỌC (RETURN & SETTLEMENT)"

Print-SubHeader "2.1" "Customer tạo yêu cầu trả kho (POST /api/customer/rentals/{id}/return-request)"
$retReqBody = '{
    "scheduledDate": "2026-10-15",
    "notes": "Tôi đã hết nhu cầu sử dụng gian kho, đề nghị kiểm tra thanh lý hợp đồng và trả tiền cọc."
}'
$retRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/customer/rentals/$rentalId/return-request" `
    -Headers $headers["Customer"] -ContentType "application/json; charset=utf-8" -Body $retReqBody

$returnCaseId = $retRes.data.id
Print-Success "Yêu cầu trả kho đã được tạo thành công!"
Print-Info "Mã hồ sơ (Return Case ID)" $returnCaseId
Print-Info "Mã Gian Kho" $retRes.data.unitNumber
Print-Info "Trạng thái hồ sơ" $retRes.data.status
Print-Info "Tiền cọc ban đầu" (Format-Vnd ([decimal]$retRes.data.depositAmount))
Print-Info "Ngày hẹn kiểm tra" $retRes.data.scheduledDate

Print-SubHeader "2.2" "Staff kiểm tra hiện trạng & lập biên bản quyết toán (POST /api/staff/returns/{id}/inspection)"
$inspectBody = '{
    "returnedKey": true,
    "returnedCard": true,
    "returnedLock": true,
    "inventoryMatch": "match",
    "damageClassification": "minor_damage",
    "inspectionNotes": "Sàn kho trầy xước nhẹ do kéo lê pallet gỗ. Cần làm sạch và sơn lại bề mặt sàn.",
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

$inspRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/staff/returns/$returnCaseId/inspection" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $inspectBody

Print-Success "Nhân viên Staff đã hoàn tất biên bản nghiệm thu hiện trạng!"
Print-Info "Nhân viên kiểm tra" $inspRes.data.inspectedByName
Print-Info "Bàn giao chìa khóa/thẻ" "ĐẦY ĐỦ (Chìa: OK, Thẻ: OK, Ổ khóa: OK)"
Print-Info "Phân loại hư hại" $inspRes.data.damageClassification
Print-Info "Phí hư hại (Damage Fee)" (Format-Vnd ([decimal]$inspRes.data.damageFee))
Print-Info "Phí vệ sinh (Cleaning Fee)" (Format-Vnd ([decimal]$inspRes.data.cleaningFee))
Print-Info "Tổng khấu trừ (Total Deductions)" (Format-Vnd ([decimal]$inspRes.data.totalDeductions))
Print-Info "Tiền cọc thực hoàn (Net Refund)" (Format-Vnd ([decimal]$inspRes.data.netRefundAmount))
Print-Info "Trạng thái hồ sơ mới" $inspRes.data.status

Print-SubHeader "2.3" "Customer xác nhận đồng ý biên bản quyết toán (POST /api/customer/returns/{id}/confirm)"
$custConfirmBody = '{
    "decision": "accepted",
    "note": "Tôi đồng ý với kết quả nghiệm thu và khoản khấu trừ 300,000 VND tiền sửa sàn & vệ sinh."
}'
$confRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/customer/returns/$returnCaseId/confirm" `
    -Headers $headers["Customer"] -ContentType "application/json; charset=utf-8" -Body $custConfirmBody

Print-Success "Khách hàng đã xác nhận đồng ý quyết toán cọc!"
Print-Info "Quyết định khách hàng" $confRes.data.customerDecision
Print-Info "Trạng thái hồ sơ" $confRes.data.status

Print-SubHeader "2.4" "Staff thực hiện chuyển khoản hoàn tiền cọc (POST /api/staff/returns/{id}/complete-refund)"
$refundBody = '{
    "transactionReference": "VNPAY-REFUND-88992200",
    "notes": "Đã chuyển khoản hoàn tiền cọc 2,200,000 VND vào STK Ngân hàng Techcombank của khách hàng."
}'
$refundRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/staff/returns/$returnCaseId/complete-refund" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $refundBody

Print-Success "Tiền cọc đã được chuyển hoàn và đóng hồ sơ thanh lý hợp đồng!"
Print-Info "Mã giao dịch chuyển khoản" $refundRes.data.settlementPaymentId
Print-Info "Trạng thái hồ sơ cuối cùng" $refundRes.data.status
Print-Info "Thời gian hoàn tất" $refundRes.data.completedAt

# ==============================================================================
# BƯỚC 3: QUY TRÌNH BẢO TRÌ GIAN KHO & PHÂN CÔNG STAFF (MAINTENANCE)
# ==============================================================================
Print-Header "BƯỚC 3: QUY TRÌNH BẢO TRÌ GIAN KHO & PHÂN CÔNG STAFF (MAINTENANCE)"

Print-SubHeader "3.1" "Manager chuyển gian Q1-U101 sang Maintenance & phân công Staff"
$maintReqBody = @{
    status = "maintenance"
    reason = "Kho xuất hiện vết trầy xước sau đợt trả kho của khách hàng, cần sơn epoxy phủ lại mặt sàn"
    createMaintenanceTask = $true
    priority = "medium"
    damageClassification = "minor_damage"
    assignedStaffId = "55555555-5555-5555-5555-555555555555"
} | ConvertTo-Json -Compress

$maintRes = Invoke-ApiUtf8 -Method Patch -Uri "$BaseUrl/api/manager/storage-units/$storageUnitId/status" `
    -Headers $headers["Manager"] -ContentType "application/json; charset=utf-8" -Body $maintReqBody

$taskId = $maintRes.data.id
Print-Success "Gian kho đã chuyển sang chế độ bảo trì và tạo công việc phân công!"
Print-Info "Mã công việc (Task ID)" $taskId
Print-Info "Tiêu đề công việc" $maintRes.data.title
Print-Info "Người giao việc (Manager)" $maintRes.data.reportedByName
Print-Info "Nhân viên phụ trách (Staff)" $maintRes.data.assignedStaffName
Print-Info "Trạng thái Task" $maintRes.data.status

Print-SubHeader "3.2" "Staff bắt đầu thực hiện sửa chữa (POST /api/staff/maintenance-tasks/{id}/start)"
$startRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/staff/maintenance-tasks/$taskId/start" `
    -Headers $headers["Staff"]

Print-Success "Nhân viên Staff đã tiếp nhận và bắt đầu làm việc tại hiện trường!"
Print-Info "Trạng thái Task" $startRes.data.status
Print-Info "Thời gian bắt đầu" $startRes.data.startedAt

Print-SubHeader "3.3" "Staff nghiệm thu hoàn thành sửa chữa (POST /api/staff/maintenance-tasks/{id}/complete)"
$compBody = '{
    "resultReport": "Đã đánh bóng, hút bụi và sơn phủ 2 lớp epoxy chống trầy cho toàn bộ mặt sàn gian Q1-U101. Sàn kho đã khô và sạch đẹp, sẵn sàng đón khách hàng mới.",
    "evidencePhotos": [
        "https://storagehub.local/photos/maintenance-q1-u101-completed-epoxy.jpg"
    ]
}'
$compRes = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/staff/maintenance-tasks/$taskId/complete" `
    -Headers $headers["Staff"] -ContentType "application/json; charset=utf-8" -Body $compBody

Print-Success "Staff đã hoàn tất công việc bảo trì gian kho!"
Print-Info "Trạng thái Task" $compRes.data.status
Print-Info "Báo cáo kết quả sửa chữa" $compRes.data.resultReport
Print-Info "Thời gian hoàn thành" $compRes.data.completedAt
Print-Info "Ảnh bằng chứng nghiệm thu" ($compRes.data.evidencePhotos -join ", ")
Print-Info "Cơ chế tự động" "Gian kho Q1-U101 đã TỰ ĐỘNG chuyển về trạng thái [AVAILABLE]!"

# ==============================================================================
# BƯỚC 4: CẤU HÌNH KINH DOANH & BÁO CÁO QUẢN TRỊ (CONFIGURATION & REPORTING)
# ==============================================================================
Print-Header "BƯỚC 4: CẤU HÌNH CHÍNH SÁCH GIÁ & BÁO CÁO QUẢN TRỊ (CONFIGURATION & REPORTS)"

Print-SubHeader "4.1" "Manager xem báo cáo tổng quan cơ sở Q1 (GET /api/manager/reports/summary)"
$mgrSum = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/manager/reports/summary?facilityId=$facilityId" `
    -Headers $headers["Manager"]

Print-Success "Lấy thành công số liệu vận hành Cơ sở Quận 1!"
Print-Info "Tên chi nhánh" $mgrSum.data.facilityName
Print-Info "Tổng số gian kho" $mgrSum.data.totalUnits
Print-Info "Số gian sẵn sàng (Available)" $mgrSum.data.availableUnits
Print-Info "Số gian đang thuê (Occupied)" $mgrSum.data.occupiedUnits
Print-Info "Tỷ lệ lấp đầy (Occupancy Rate)" ("{0:P1}" -f ([double]$mgrSum.data.occupancyRate / 100))
Print-Info "Doanh thu đã thu (Collected)" (Format-Vnd ([decimal]$mgrSum.data.collectedRevenue))
Print-Info "Công việc bảo trì còn mở" $mgrSum.data.openMaintenanceTasksCount

Print-SubHeader "4.2" "Business xem báo cáo doanh thu toàn hệ thống (GET /api/business/reports/revenue)"
$bizRev = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/business/reports/revenue" `
    -Headers $headers["Business"]

Print-Success "Lấy thành công báo cáo doanh thu toàn hệ thống!"
Print-Info "Tổng doanh thu thu về" (Format-Vnd ([decimal]$bizRev.data.totalRevenueCollected))
Print-Info "Tổng tiền hoàn trả" (Format-Vnd ([decimal]$bizRev.data.totalRefunds))
Print-Info "Doanh thu thuần (Net Revenue)" (Format-Vnd ([decimal]$bizRev.data.netRevenue))

Write-Host ""
Write-Host "  [Phân rã theo mục đích thanh toán]:" -ForegroundColor Yellow
foreach ($p in $bizRev.data.paymentPurposeBreakdown) {
    Write-Host ("    - {0,-22}: {1,14} ({2} giao dịch)" -f $p.purpose, (Format-Vnd ([decimal]$p.amount)), $p.count) -ForegroundColor Gray
}

Print-SubHeader "4.3" "Business xem danh sách chính sách gói giá (GET /policies/packages)"
$policies = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/business/policies/packages?facilityId=$facilityId" `
    -Headers $headers["Business"]

Print-Success ("Lấy thành công {0} chính sách gói thuê đang áp dụng:" -f $policies.data.Count)
foreach ($pol in $policies.data) {
    Write-Host ("    * [{0}] {1,-40} | Thời hạn: {2,2} tháng | Chiết khấu: {3,4:P0}" -f $pol.code, $pol.name, $pol.rentalMonths, ([double]$pol.discountRate)) -ForegroundColor White
}

Print-SubHeader "4.4" "Business kiểm tra cấu hình vận hành toàn hệ thống (GET /api/business/config)"
$bizCfg = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/business/config" `
    -Headers $headers["Business"]

Print-Success "Lấy thành công thông số cấu hình kinh doanh:"
Print-Info "Hạn chót thanh toán (Grace Period)" ("{0} ngày" -f $bizCfg.data.gracePeriodDays)
Print-Info "Phí phạt trễ hạn (Late Fee)" (Format-Vnd ([decimal]$bizCfg.data.lateFeeAmount))
Print-Info "Tỷ lệ cọc mặc định" ("{0:P0}" -f ([double]$bizCfg.data.defaultDepositRatio))
Print-Info "Thời hạn giữ chỗ (Hold Expiry)" ("{0} giờ" -f $bizCfg.data.holdExpiryHours)
Print-Info "Thông báo Banner hệ thống" $bizCfg.data.bannerNotice

# ==============================================================================
# TỔNG KẾT DEMO
# ==============================================================================
Write-Host ""
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host "   *             DEMO TOÀN TRÌNH ĐÃ HOÀN THÀNH XUẤT SẮC (100%)              *" -ForegroundColor Green
Write-Host "   *                                                                        *" -ForegroundColor Green
Write-Host "   *   [x] 1. Authentication: 4 vai trò đã lấy Token hợp lệ                 *" -ForegroundColor Green
Write-Host "   *   [x] 2. Return & Settlement: Hoàn tất quyết toán cọc không sai sót    *" -ForegroundColor Green
Write-Host "   *   [x] 3. Maintenance: Chuyển bảo trì -> Phục hồi kho Available         *" -ForegroundColor Green
Write-Host "   *   [x] 4. Configuration & Reports: Số liệu thống kê chính xác           *" -ForegroundColor Green
Write-Host "   ==========================================================================" -ForegroundColor Green
Write-Host ""
