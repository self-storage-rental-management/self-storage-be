param(
    [string]$BaseUrl = "http://localhost:8080"
)

try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "      KIEM TRA TOAN DIEN CAC API CUA BO (BUSINESS OPERATIONS)    " -ForegroundColor Yellow
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Login
$loginBody = @{
    email = "business@storagehub.demo"
    password = "Business@1234!"
} | ConvertTo-Json

try {
    $loginRes = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
    Write-Host "[OK] Dang nhap thanh cong vai tro BO!" -ForegroundColor Green
    Write-Host ("  - Ho ten:  " + $loginRes.data.actor.fullName) -ForegroundColor White
    Write-Host ("  - Email:   " + $loginRes.data.actor.email) -ForegroundColor White
    Write-Host ("  - Roles:   " + ($loginRes.data.actor.roles -join ", ")) -ForegroundColor White
    $token = $loginRes.data.accessToken
} catch {
    Write-Host "[FAIL] Dang nhap that bai: $_" -ForegroundColor Red
    exit 1
}

$headers = @{
    Authorization = "Bearer $token"
}

# 2. GET /api/business/config
try {
    $cfg = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/config" -Headers $headers
    Write-Host ""
    Write-Host "[OK] GET /api/business/config (Cau hinh van hanh)" -ForegroundColor Green
    Write-Host ("  - Grace Period:       " + $cfg.data.gracePeriodDays + " ngay") -ForegroundColor White
    Write-Host ("  - Late Fee:           " + ("{0:N0}" -f $cfg.data.lateFeeAmount) + " VND") -ForegroundColor White
    Write-Host ("  - Deposit Ratio:      " + ($cfg.data.defaultDepositRatio * 100) + "%") -ForegroundColor White
    Write-Host ("  - Hold Expiry:        " + $cfg.data.holdExpiryHours + " gio") -ForegroundColor White
    Write-Host ("  - DIM Divisor:        " + $cfg.data.dimDivisor) -ForegroundColor White
    Write-Host ("  - Banner Notice:      " + $cfg.data.bannerNotice) -ForegroundColor White
} catch {
    Write-Host "[FAIL] GET /api/business/config: $_" -ForegroundColor Red
}

# 3. GET /api/business/policies/packages
try {
    $pkgs = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/policies/packages" -Headers $headers
    Write-Host ""
    Write-Host "[OK] GET /api/business/policies/packages (Goi chinh sach thue)" -ForegroundColor Green
    Write-Host ("  - Tong so goi:        " + $pkgs.data.Count + " goi") -ForegroundColor White
    $sample = $pkgs.data | Select-Object -First 3
    foreach ($p in $sample) {
        Write-Host ("    * [" + $p.code + "] " + $p.name + " (" + $p.rentalMonths + " thang, giam " + ($p.discountRate * 100) + "%)") -ForegroundColor Gray
    }
} catch {
    Write-Host "[FAIL] GET /api/business/policies/packages: $_" -ForegroundColor Red
}

# 4. GET /api/business/reports/performance
try {
    $perf = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/reports/performance" -Headers $headers
    Write-Host ""
    Write-Host "[OK] GET /api/business/reports/performance (Hieu suat mang luoi)" -ForegroundColor Green
    Write-Host ("  - Tong so co so:      " + $perf.data.totalFacilities) -ForegroundColor White
    Write-Host ("  - Tong suc chua kho:  " + $perf.data.totalCapacityUnits + " gian") -ForegroundColor White
    Write-Host ("  - So gian dang thue:  " + $perf.data.totalOccupiedUnits + " gian") -ForegroundColor White
    Write-Host ("  - Ty le lap day he thong: " + $perf.data.systemOccupancyRate + "%") -ForegroundColor Yellow
    foreach ($fac in $perf.data.facilityComparisons) {
        Write-Host ("    * " + $fac.facilityName + ": " + $fac.occupiedUnits + "/" + $fac.totalUnits + " gian (" + $fac.occupancyRate + "%), Active rentals: " + $fac.activeRentals + ", Open returns: " + $fac.openReturns + ", Open maintenance: " + $fac.openMaintenance) -ForegroundColor Cyan
    }
} catch {
    Write-Host "[FAIL] GET /api/business/reports/performance: $_" -ForegroundColor Red
}

# 5. GET /api/business/reports/revenue
try {
    $rev = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/business/reports/revenue?months=6" -Headers $headers
    Write-Host ""
    Write-Host "[OK] GET /api/business/reports/revenue (Bao cao doanh thu 6 thang)" -ForegroundColor Green
    Write-Host ("  - Tong doanh thu thu ve:  " + ("{0:N0}" -f $rev.data.totalRevenueCollected) + " VND") -ForegroundColor Yellow
    Write-Host "  - Doanh thu theo thang:" -ForegroundColor White
    foreach ($m in $rev.data.monthlyBreakdown) {
        Write-Host ("    * Thang " + $m.month + ": " + ("{0,15:N0}" -f $m.revenue) + " VND (" + $m.transactionsCount + " giao dich)") -ForegroundColor Gray
    }
    Write-Host "  - Doanh thu theo muc dich:" -ForegroundColor White
    foreach ($p in $rev.data.paymentPurposeBreakdown) {
        Write-Host ("    * " + $p.purpose + ": " + ("{0,15:N0}" -f $p.amount) + " VND (" + $p.count + " gd)") -ForegroundColor Gray
    }
} catch {
    Write-Host "[FAIL] GET /api/business/reports/revenue: $_" -ForegroundColor Red
}

# 6. Check Frontend Web
try {
    $fe = Invoke-WebRequest -Method Get -Uri "http://localhost:8443" -UseBasicParsing -TimeoutSec 3
    Write-Host ""
    Write-Host "[OK] Frontend Web dang phuc vu tot tai http://localhost:8443 (HTTP Status: 200)" -ForegroundColor Green
} catch {
    Write-Host "[FAIL] Frontend Web chua phan hoi: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "              TAT CA HE THONG SAN SANG CHO TEST BO!              " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
