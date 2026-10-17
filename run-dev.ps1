$env:STORAGEHUB_DB_URL = "jdbc:mysql://localhost:3306/storagehub_local?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:STORAGEHUB_DB_USERNAME = "root"
$env:STORAGEHUB_DB_PASSWORD = if ($env:STORAGEHUB_DB_PASSWORD) { $env:STORAGEHUB_DB_PASSWORD } else { "1234" }
$env:STORAGEHUB_ALLOWED_ORIGIN = "http://localhost:5173,http://localhost:8443,http://localhost:8444"
$env:STORAGEHUB_JWT_SECRET = "vzFQm99eQEzIAvLPiTNKCJIn1v8iLDOFdZtXS1JTDrU="
$env:STORAGEHUB_MAIL_ENABLED = "false"
$env:STORAGEHUB_EXPOSE_DEVELOPMENT_CODE = "true"
$env:STORAGEHUB_FILE_STORAGE_PATH = "./uploads"
$env:STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED = "true"
$env:STORAGEHUB_BOOTSTRAP_ADMIN_EMAIL = "admin@storagehub.demo"
$env:STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD = "Admin@1234!"
$env:STORAGEHUB_BOOTSTRAP_ADMIN_FULL_NAME = "StorageHub Administrator"
$env:STORAGEHUB_INTEGRATION_LOCAL_DEMO_OPERATIONAL_SOURCES_ENABLED = "true"
$env:STORAGEHUB_FLYWAY_LOCATIONS = "classpath:db/migration,classpath:db/paymentmigration,classpath:db/renewal-hold-migration,classpath:db/renewal-local-demo-migration"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  KHỞI ĐỘNG STORAGEHUB BACKEND (LOCAL DEV)" -ForegroundColor Yellow
Write-Host "  Backend API:  http://localhost:8080" -ForegroundColor Green
Write-Host "  Swagger UI:   http://localhost:8080/swagger-ui.html" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Cyan

Set-Location $PSScriptRoot
.\mvnw.cmd spring-boot:run
