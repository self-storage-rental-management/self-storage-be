@echo off
chcp 65001 >nul
title StorageHub MySQL Runner
echo =======================================================
echo   ĐANG KHỞI ĐỘNG MYSQL (DOCKER)
echo =======================================================
cd /d "%~dp0"
docker compose up -d
echo.
echo =======================================================
echo  MySQL đang chạy nền tại: localhost:3306
echo  Database: storagehub_local
echo  Username: root
echo  Password: 1234
echo  phpMyAdmin (Web GUI): http://localhost:8085
echo =======================================================
pause
