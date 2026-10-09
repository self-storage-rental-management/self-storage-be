@echo off
chcp 65001 >nul
title StorageHub E2E Data Importer
echo ==============================================================================
echo   ĐANG NẠP DỮ LIỆU MẪU TOÀN TRÌNH (BO - KHÁCH ĐẶT KHO - THANH TOÁN - HỢP ĐỒNG)
echo ==============================================================================
echo.

set "MYSQL_EXE=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
if not exist "%MYSQL_EXE%" (
    set "MYSQL_EXE=mysql"
)

"%MYSQL_EXE%" --default-character-set=utf8mb4 -uroot -p1234 storagehub_local < "%~dp0scripts\seed-e2e-business-flow.sql"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [THÀNH CÔNG] Đã nạp trọn vẹn luồng dữ liệu mẫu vào database storagehub_local!
    echo   - Cơ sở mới: HCM-Q7-F01 (Kho Việt - Quận 7)
    echo   - 3 Loại kho: S (Mini), M (Gia đình), L (Doanh nghiệp)
    echo   - Gian kho: Q7-U101 (Occupied), Q7-U102 (Available), Q7-U201 (Available), Q7-U103 (Maintenance)
    echo   - Đơn đặt kho: RES-Q7-2026-0001 (Khách đặt gói 6 tháng, giảm giá 10%%)
    echo   - 2 Giao dịch thanh toán: Cọc giữ chỗ (7,000,000 VND) + Tiền thuê (37,800,000 VND)
    echo   - Hợp đồng đã ký: CTR-Q7-2026-0001
    echo   - Hợp đồng thuê kích hoạt: Q7-U101 đang Active!
) else (
    echo.
    echo [LỖI] Không thể nạp dữ liệu vào MySQL.
    echo Hãy đảm bảo dịch vụ MySQL đang chạy trên localhost:3306 với user root và password 1234.
)

echo.
pause
