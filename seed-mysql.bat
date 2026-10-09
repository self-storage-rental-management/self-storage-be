@echo off
chcp 65001 >nul
echo =======================================================
echo   Đang nạp seed demo data vào MySQL container...
echo =======================================================
docker exec -i storagehub-mysql mysql -uroot -p1234 storagehub_local < "%~dp0scripts\seed-demo-data.sql"
if %ERRORLEVEL% EQU 0 (
    echo [OK] Nạp dữ liệu mẫu thành công!
) else (
    echo [LỖI] Không thể nạp dữ liệu.
    echo Lưu ý: Hãy đảm bảo MySQL container đang chạy và Backend Spring Boot đã chạy ít nhất 1 lần để Hibernate tạo sẵn các bảng.
)
pause
