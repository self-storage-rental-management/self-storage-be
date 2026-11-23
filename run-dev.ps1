$env:STORAGEHUB_DB_URL = "jdbc:mysql://localhost:3306/storagehub_local?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:STORAGEHUB_DB_USERNAME = "root"
$env:STORAGEHUB_ALLOWED_ORIGIN = "http://localhost:5173,http://localhost:8443,http://localhost:8444"
foreach ($setting in @(
    'STORAGEHUB_DB_PASSWORD', 'STORAGEHUB_JWT_SECRET',
    'STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED', 'STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD',
    'STORAGEHUB_MAIL_ENABLED', 'STORAGEHUB_MAIL_FROM', 'STORAGEHUB_MAIL_HOST',
    'STORAGEHUB_MAIL_PORT', 'STORAGEHUB_MAIL_USERNAME', 'STORAGEHUB_MAIL_PASSWORD',
    'STORAGEHUB_MAIL_SUPPORT', 'STORAGEHUB_MAIL_SMTP_AUTH', 'STORAGEHUB_MAIL_SMTP_STARTTLS',
    'STORAGEHUB_FRONTEND_BASE_URL', 'STORAGEHUB_LOGIN_URL', 'STORAGEHUB_SECURITY_URL',
    'STORAGEHUB_ACCOUNT_URL', 'STORAGEHUB_EXPOSE_DEVELOPMENT_CODE',
    'STORAGEHUB_VNPAY_ENABLED', 'STORAGEHUB_VNPAY_TMN_CODE', 'STORAGEHUB_VNPAY_HASH_SECRET',
    'STORAGEHUB_VNPAY_PAY_URL', 'STORAGEHUB_VNPAY_API_URL', 'STORAGEHUB_VNPAY_RETURN_URL',
    'STORAGEHUB_VNPAY_FRONTEND_RETURN_URL', 'STORAGEHUB_VNPAY_IPN_URL'
)) {
    if (-not [Environment]::GetEnvironmentVariable($setting, 'Process')) {
        $userValue = [Environment]::GetEnvironmentVariable($setting, 'User')
        if ($userValue) {
            [Environment]::SetEnvironmentVariable($setting, $userValue, 'Process')
        }
    }
}
if (-not $env:STORAGEHUB_DB_PASSWORD) {
    throw 'STORAGEHUB_DB_PASSWORD must be supplied through a process or user environment variable.'
}
if (-not $env:STORAGEHUB_JWT_SECRET) {
    $jwtBytes = New-Object byte[] 32
    $jwtGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $jwtGenerator.GetBytes($jwtBytes) } finally { $jwtGenerator.Dispose() }
    $env:STORAGEHUB_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
}
$env:STORAGEHUB_FILE_STORAGE_PATH = "./uploads"
if (-not $env:STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED) {
    $env:STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED = if ($env:STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD) { "true" } else { "false" }
}
if ($env:STORAGEHUB_BOOTSTRAP_ADMIN_ENABLED -eq "true" -and -not $env:STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD) {
    throw 'STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD is required when bootstrap admin is enabled.'
}
$env:STORAGEHUB_BOOTSTRAP_ADMIN_EMAIL = "admin@storagehub.demo"
$env:STORAGEHUB_BOOTSTRAP_ADMIN_FULL_NAME = "StorageHub Administrator"
$env:STORAGEHUB_INTEGRATION_LOCAL_DEMO_OPERATIONAL_SOURCES_ENABLED = "true"
$env:STORAGEHUB_FLYWAY_LOCATIONS = "classpath:db/migration,classpath:db/paymentmigration,classpath:db/renewal-hold-migration,classpath:db/renewal-local-demo-migration"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  KHỞI ĐỘNG STORAGEHUB BACKEND (LOCAL DEV)" -ForegroundColor Yellow
Write-Host "  Backend API:  http://localhost:8080" -ForegroundColor Green
Write-Host "  Swagger UI:   http://localhost:8080/swagger-ui.html" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Cyan

Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path '../.local/runtimes/project-jdk21-home.txt')) {
    $env:JAVA_HOME = (Get-Content '../.local/runtimes/project-jdk21-home.txt' -Raw).Trim()
}
if (-not $env:MAVEN_USER_HOME -and (Test-Path '../.local/maven-wrapper-home/wrapper/dists')) {
    $env:MAVEN_USER_HOME = (Resolve-Path '../.local/maven-wrapper-home').Path
}
.\mvnw.cmd spring-boot:run
