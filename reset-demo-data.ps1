param(
    [string]$CustomerEmail = 'thongvinh2@gmail.com',
    [string]$Container = 'storagehub-mysql',
    [string]$Database = 'storagehub_local'
)

$ErrorActionPreference = 'Stop'
$batch = 'QD' + (Get-Date -Format 'yyMMddHHmmss')

function Import-Sql([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Không tìm thấy: $Path" }
    (Get-Content -LiteralPath $Path -Raw).Replace('USE storagehub_local;', "USE $Database;") |
        docker exec -i $Container sh -c "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD mysql --default-character-set=utf8mb4 -uroot $Database"
    if ($LASTEXITCODE -ne 0) { throw "Không thể chạy SQL: $Path" }
}

Import-Sql (Join-Path $PSScriptRoot 'scripts\clean-presentation-db.sql')
Import-Sql (Join-Path $PSScriptRoot 'scripts\seed-demo-data.sql')

$customerExists = @"
SELECT COUNT(*) FROM users WHERE email='$($CustomerEmail.Replace("'", "''"))';
"@ | docker exec -i $Container sh -c "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD mysql -N -B -uroot $Database"
if ($LASTEXITCODE -ne 0) { throw 'Không thể kết nối database.' }
if ([int]$customerExists -ne 1) { throw "Không tìm thấy tài khoản $CustomerEmail sau khi seed." }

& (Join-Path $PSScriptRoot 'scripts\seed-renewal-template.ps1') -Database $Database

& (Join-Path $PSScriptRoot 'scripts\prepare-overdue-web-demo.ps1') `
    -Batch $batch -CustomerEmail $CustomerEmail -Database $Database
& (Join-Path $PSScriptRoot 'scripts\seed-renewal-14-demo-cases.ps1') `
    -Batch $batch -Database $Database

Write-Host "Đã reset dữ liệu và tạo mã GH-2611-000001 đến GH-2611-000014 cho $CustomerEmail (batch $batch)." -ForegroundColor Green
