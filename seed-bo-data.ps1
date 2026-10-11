# Script nap du lieu test cho phan he Ban Dieu Hanh (BO / Business Operations)
param(
    [string]$DbUser = "root",
    [string]$DbPass = "1234",
    [string]$DbName = "storagehub_local"
)

try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "==============================================================================" -ForegroundColor Cyan
Write-Host "   DANG NAP BO DU LIEU TEST PHAN HE BAN DIEU HANH (BO / BUSINESS OPERATIONS)  " -ForegroundColor Yellow
Write-Host "==============================================================================" -ForegroundColor Cyan

$sqlFile = Join-Path $PSScriptRoot "scripts\seed-bo-test-data.sql"
if (-not (Test-Path $sqlFile)) {
    Write-Host "[ERROR] Khong tim thay file $sqlFile" -ForegroundColor Red
    exit 1
}

cmd /c "chcp 65001 >nul && mysql --default-character-set=utf8mb4 -u $DbUser -p$DbPass $DbName < `"$sqlFile`""

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "[THANH CONG] Da nap tron ven bo du lieu test BO vao database $DbName!" -ForegroundColor Green
    Write-Host "  - Cau hinh van hanh he thong (Grace Period, Late Fee, Deposit Ratio, Banner Notice)" -ForegroundColor White
    Write-Host "  - Mang luoi 4 co so: Q1, Q7, Binh Duong, Thu Duc" -ForegroundColor White
    Write-Host "  - 4 Loai kho tieu chuan: S, M, L, XL" -ForegroundColor White
    Write-Host "  - Da dang trang thai gian kho: Occupied, Available, Maintenance, Reserved" -ForegroundColor White
    Write-Host "  - 16 Goi chinh sach thue (1M, 3M, 6M, 12M)" -ForegroundColor White
    Write-Host "  - Chuoi thanh toan doanh thu 6 thang (05/2026 - 10/2026) cho Bieu do Doanh thu" -ForegroundColor White
    Write-Host "  - 6 Hop dong thue dang kich hoat (Active Rentals)" -ForegroundColor White
    Write-Host "  - 3 Ho so tra kho dang xu ly (Open Returns)" -ForegroundColor White
    Write-Host "  - 4 Tac vu bao tri dang mo (Open Maintenance)" -ForegroundColor White
    Write-Host ""
    Write-Host "Tai khoan test BO:" -ForegroundColor Cyan
    Write-Host "  - Email:    business@storagehub.demo" -ForegroundColor Yellow
    Write-Host "  - Mat khau: Business@1234!" -ForegroundColor Yellow
    Write-Host "  - Vai tro:  BUSINESS (Ban Dieu Hanh / BO)" -ForegroundColor Yellow
} else {
    Write-Host "[ERROR] Nap du lieu that bai. Kiem tra MySQL dang chay tren localhost:3306" -ForegroundColor Red
}
