@echo off
chcp 65001 >nul
title StorageHub Backend Dev Server

echo ========================================================
echo   KHỞI ĐỘNG STORAGEHUB BACKEND (LOCAL DEV)
echo ========================================================

REM Tự động bổ sung JDK 21 vào PATH nếu chưa nhận
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
    set "PATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin;%ProgramFiles%\Java\jdk-21\bin;%PATH%"
)
set "MAVEN_OPTS=-Xms64m -Xmx512m"

set "STORAGEHUB_DB_URL=jdbc:mysql://localhost:3306/storagehub_local?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
set "STORAGEHUB_DB_USERNAME=root"
set "STORAGEHUB_DB_PASSWORD=root"
set "STORAGEHUB_ALLOWED_ORIGIN=http://localhost:5173,http://localhost:8443"
set "STORAGEHUB_JWT_SECRET=dGhpcy1pcy1hLXNlY3VyZS0yNTYtYml0LWtleS1mb3Itand0LXN0b3JhZ2VodWI="
set "STORAGEHUB_MAIL_ENABLED=false"
set "STORAGEHUB_EXPOSE_DEVELOPMENT_CODE=true"
set "STORAGEHUB_FILE_STORAGE_PATH=./uploads"
set "STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED=true"
set "STORAGEHUB_BOOTSTRAP_ADMIN_EMAIL=admin@storagehub.demo"
set "STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD=Admin@1234!"
set "STORAGEHUB_BOOTSTRAP_ADMIN_FULL_NAME=StorageHub Administrator"

cd /d "%~dp0"
echo Đang chạy backend Spring Boot qua mvnw.cmd...
echo API Base: http://localhost:8080
echo Swagger UI: http://localhost:8080/swagger-ui.html
echo ========================================================
call mvnw.cmd spring-boot:run
pause
