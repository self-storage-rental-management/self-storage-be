@echo off
chcp 65001 >nul
title StorageHub - Nạp Dữ Liệu Test BO (Business Operations)
echo ==============================================================================
echo   ĐANG NẠP BỘ DỮ LIỆU TEST PHÂN HỆ BAN ĐIỀU HÀNH (BO / BUSINESS OPERATIONS)
echo ==============================================================================
echo.

set "MYSQL_EXE=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
if not exist "%MYSQL_EXE%" (
    set "MYSQL_EXE=mysql"
)

"%MYSQL_EXE%" --default-character-set=utf8mb4 -uroot -p1234 storagehub_local < "%~dp0scripts\seed-bo-test-data.sql"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [THÀNH CÔNG] Đã nạp thành công bộ dữ liệu test BO vào database storagehub_local!
    echo   - Cấu hình vận hành (Grace Period, Late Fee, Deposit Ratio, Notice)
    echo   - Mạng lưới 4 cơ sở: Q1, Q7, Bình Dương, Thủ Đức
    echo   - 4 Quy cách loại kho tiêu chuẩn (S, M, L, XL)
    echo   - Đa dạng trạng thái gian kho (Occupied, Available, Maintenance, Reserved)
    echo   - Các gói chính sách thuê (1M, 3M, 6M, 12M)
    echo   - Chuỗi thanh toán doanh thu 6 tháng (05/2026 - 10/2026) cho Recharts Chart
    echo   - Hợp đồng thuê đang chạy (Active Rentals)
    echo   - Hồ sơ trả kho đang mở (Open Return Cases)
    echo   - Tác vụ bảo trì đang thực hiện (Open Maintenance Tasks)
    echo.
    echo Tài khoản đăng nhập test BO:
    echo   - Email:    business@storagehub.demo
    echo   - Mật khẩu: Business@1234!
    echo   - Vai trò:  BUSINESS (Ban Điều Hành - BOM)
) else (
    echo.
    echo [LỖI] Không thể nạp dữ liệu vào MySQL.
    echo Vui lòng đảm bảo dịch vụ MySQL đang chạy trên localhost:3306 với user root và password 1234.
)

echo.
pause
