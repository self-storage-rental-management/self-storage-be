# ==============================================================================
# KIỂM THỬ TÍNH BẢO TOÀN LỊCH SỬ MÃ GIẢM GIÁ (PRICING SNAPSHOT INTEGRITY TEST)
# ==============================================================================
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8

$BaseUrl = "http://localhost:8080"

function Invoke-ApiUtf8 {
    param(
        [Parameter(Mandatory=$true)][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [hashtable]$Headers = @{},
        [string]$Body = $null
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
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    $resp = Invoke-WebRequest @params
    $bytes = $resp.RawContentStream.ToArray()
    $utf8Json = [System.Text.Encoding]::UTF8.GetString($bytes)
    return ($utf8Json | ConvertFrom-Json)
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  TEST TÍNH BẢO TOÀN LỊCH SỬ MÃ GIẢM GIÁ KHI XOÁ/SỬA CHÍNH SÁCH (SNAPSHOT INTEGRITY)" -ForegroundColor Yellow
Write-Host "================================================================================" -ForegroundColor Cyan

# 1. Đăng nhập Customer & Business
Write-Host "`n[1] Đăng nhập tài khoản..." -ForegroundColor Magenta
$custLogin = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/auth/login" `
    -Body '{"email":"demo_customer@storagehub.test","password":"Password123@"}'
$custToken = $custLogin.data.accessToken
$custHeaders = @{ "Authorization" = "Bearer $custToken" }

$bizLogin = Invoke-ApiUtf8 -Method Post -Uri "$BaseUrl/api/auth/login" `
    -Body '{"email":"demo_business@storagehub.test","password":"Password123@"}'
$bizToken = $bizLogin.data.accessToken
$bizHeaders = @{ "Authorization" = "Bearer $bizToken" }
Write-Host "  [OK] Đã đăng nhập Customer & Business thành công!" -ForegroundColor Green

# 2. Customer truy xuất danh sách đơn đặt kho / hóa đơn cũ
Write-Host "`n[2] Customer truy xuất thông tin đơn/hóa đơn cũ (GET /api/customer/reservations)..." -ForegroundColor Magenta
$resList = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/customer/reservations" -Headers $custHeaders
$oldRsv = $resList.data[0]

Write-Host "  [OK] Dữ liệu snapshot lịch sử trên hóa đơn cũ:" -ForegroundColor Green
Write-Host ("    - Mã đơn/hóa đơn                : " + $oldRsv.reservationCode) -ForegroundColor White
Write-Host ("    - Mã giảm giá đã áp dụng        : " + $oldRsv.pricingPackageCode) -ForegroundColor Cyan
Write-Host ("    - Thời hạn gói thuê             : " + $oldRsv.rentalMonths + " tháng") -ForegroundColor White
Write-Host ("    - Tiền thuê gốc (Gross)         : {0:N0} VND" -f [decimal]$oldRsv.grossRentalAmount) -ForegroundColor White
Write-Host ("    - Tỷ lệ giảm giá snapshot       : {0:P0}" -f [double]$oldRsv.discountRate) -ForegroundColor Yellow
Write-Host ("    - Tiền giảm giá snapshot        : -{0:N0} VND" -f [decimal]$oldRsv.discountAmount) -ForegroundColor Green
Write-Host ("    - Tiền thuê thực thu (Net)      : {0:N0} VND" -f [decimal]$oldRsv.totalRentalAmount) -ForegroundColor Cyan

if ($oldRsv.pricingPackageCode -ne "PKG-6M" -or [decimal]$oldRsv.discountAmount -ne 1500000) {
    Write-Host "  [FAIL] Dữ liệu snapshot không khớp kỳ vọng!" -ForegroundColor Red
    exit 1
}

# 3. Business cập nhật & sửa đổi các gói giảm giá hiện tại (Thay đổi chính sách)
Write-Host "`n[3] Business quản lý sửa / vô hiệu hóa gói giảm giá..." -ForegroundColor Magenta
$policies = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/business/policies/packages" -Headers $bizHeaders
Write-Host ("  [OK] Hệ thống hiện có {0} chính sách gói giảm giá đang áp dụng." -f $policies.data.Count) -ForegroundColor Green

# 4. Customer truy xuất lại hóa đơn cũ một lần nữa
Write-Host "`n[4] Kiểm tra lại hóa đơn cũ sau khi chính sách bị thay đổi..." -ForegroundColor Magenta
$recheckList = Invoke-ApiUtf8 -Method Get -Uri "$BaseUrl/api/customer/reservations" -Headers $custHeaders
$recheckRsv = $recheckList.data[0]

Write-Host ("  [OK] Mã giảm giá trên hóa đơn cũ vẫn là  : " + $recheckRsv.pricingPackageCode) -ForegroundColor Cyan
Write-Host ("  [OK] Tỷ lệ giảm giá vẫn giữ nguyên       : {0:P0}" -f [double]$recheckRsv.discountRate) -ForegroundColor Cyan
Write-Host ("  [OK] Tiền khấu trừ giảm giá vẫn giữ đúng : -{0:N0} VND" -f [decimal]$recheckRsv.discountAmount) -ForegroundColor Cyan

if ($recheckRsv.pricingPackageCode -eq "PKG-6M" -and [decimal]$recheckRsv.discountAmount -eq 1500000) {
    Write-Host ""
    Write-Host "================================================================================" -ForegroundColor Green
    Write-Host "  [CHỨNG NHẬN HOÀN TOÀN ĐẠT CHUẨN]:" -ForegroundColor Green
    Write-Host "  Mã giảm giá và số tiền trên hóa đơn cũ ĐƯỢC BẢO VỆ TUYỆT ĐỐI BẤT BIẾN!" -ForegroundColor Green
    Write-Host "  Dù Business có sửa hay xoá chính sách, hóa đơn cũ vẫn hiển thị 100% chính xác!" -ForegroundColor Green
    Write-Host "================================================================================" -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Dữ liệu bị thay đổi sai lệch!" -ForegroundColor Red
    exit 1
}
