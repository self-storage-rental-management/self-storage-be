# Explicit local test rollout; never changes the shared application defaults.
param(
    [switch]$EnableRenewalPayments,
    [string]$VnpayConfigPath
)
$ErrorActionPreference = 'Stop'
$rentalLocalRunner = Join-Path $PSScriptRoot 'run-dev.ps1'
if (-not (Test-Path -LiteralPath $rentalLocalRunner)) {
    throw 'Missing local run-dev.ps1. Configure the existing local runner first.'
}
if (-not [string]::IsNullOrWhiteSpace($env:SPRING_APPLICATION_JSON)) {
    throw 'SPRING_APPLICATION_JSON is already set. Use a fresh terminal; this runner will not overwrite another configuration.'
}

# Fixed test datasource prevents inherited configuration from targeting Render/TiDB.
# Schema was backed up and the additive integration migration was reviewed locally.
$rentalLocalConfiguration = @{
    'spring.datasource.url' = 'jdbc:mysql://localhost:3306/storagehub_local?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
    'spring.jpa.hibernate.ddl-auto' = 'validate'
    'spring.flyway.locations' = @('classpath:db/migration', 'classpath:db/paymentmigration', 'classpath:db/renewal-hold-migration', 'classpath:db/renewal-local-demo-migration')
    'spring.flyway.clean-disabled' = $true
    'spring.flyway.baseline-on-migrate' = $false
    'storagehub.integration.rental-period.enabled' = $true
    'storagehub.integration.rental-period.inclusive-end' = $true
    'storagehub.integration.ledger.enabled' = $true
    'storagehub.integration.communication.enabled' = $true
    'storagehub.integration.renewal-operational-checks.enabled' = $true
    'storagehub.integration.renewal-extension-holds.enabled' = $true
    'spring.jpa.show-sql' = $false
    'logging.level.root' = 'INFO'
    'logging.level.org.hibernate.SQL' = 'WARN'
}
if ($VnpayConfigPath) {
    $rentalGatewayPath = (Resolve-Path -LiteralPath $VnpayConfigPath -ErrorAction Stop).Path
    if ((Get-Item -LiteralPath $rentalGatewayPath).PSIsContainer) {
        throw 'VnpayConfigPath must point to a JSON configuration file, not the Payment source directory.'
    }
    $rentalGatewaySettings = Get-Content -LiteralPath $rentalGatewayPath -Raw | ConvertFrom-Json
    $rentalGatewayAllowedKeys = @('app.vnpay.enabled', 'app.vnpay.tmn-code', 'app.vnpay.hash-secret',
        'app.vnpay.pay-url', 'app.vnpay.api-url', 'app.vnpay.return-url',
        'app.vnpay.frontend-return-url', 'app.vnpay.ipn-url')
    foreach ($rentalGatewaySetting in $rentalGatewaySettings.PSObject.Properties) {
        if ($rentalGatewaySetting.Name -notin $rentalGatewayAllowedKeys) {
            throw 'VNPay JSON contains an unsupported property. Only app.vnpay settings are allowed.'
        }
        $rentalLocalConfiguration[$rentalGatewaySetting.Name] = $rentalGatewaySetting.Value
    }
}
if ($EnableRenewalPayments) {
    # Explicit local opt-in uses the shared Payment merchant, never a Renewal-specific gateway.
    foreach ($rentalGatewayRequirement in @(
        @('app.vnpay.tmn-code', 'STORAGEHUB_VNPAY_TMN_CODE'),
        @('app.vnpay.hash-secret', 'STORAGEHUB_VNPAY_HASH_SECRET')
    )) {
        $rentalGatewayValue = $rentalLocalConfiguration[$rentalGatewayRequirement[0]]
        if (-not $rentalGatewayValue) {
            $rentalGatewayValue = [Environment]::GetEnvironmentVariable($rentalGatewayRequirement[1], 'Process')
        }
        if ([string]::IsNullOrWhiteSpace($rentalGatewayValue) -or $rentalGatewayValue -match '^YOUR_|^REPLACE_') {
            throw 'Shared Payment VNPay merchant settings are missing. Set the existing merchant environment variables or use -VnpayConfigPath with a populated JSON file. Credentials are never printed.'
        }
        $rentalLocalConfiguration[$rentalGatewayRequirement[0]] = $rentalGatewayValue
    }
    $rentalGatewayPayUrl = $rentalLocalConfiguration['app.vnpay.pay-url']
    if (-not $rentalGatewayPayUrl) { $rentalGatewayPayUrl = $env:STORAGEHUB_VNPAY_PAY_URL }
    $rentalGatewayApiUrl = $rentalLocalConfiguration['app.vnpay.api-url']
    if (-not $rentalGatewayApiUrl) { $rentalGatewayApiUrl = $env:STORAGEHUB_VNPAY_API_URL }
    if (($rentalGatewayPayUrl -and $rentalGatewayPayUrl -ne 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html') -or
        ($rentalGatewayApiUrl -and $rentalGatewayApiUrl -ne 'https://sandbox.vnpayment.vn/merchant_webapi/api/transaction')) {
        throw 'This local Renewal rollout supports the shared VNPay Sandbox endpoints only.'
    }
    $rentalLocalConfiguration['app.vnpay.enabled'] = $true
    $rentalLocalConfiguration['storagehub.integration.sandbox-reconciliation.enabled'] = $true
    $rentalLocalConfiguration['storagehub.integration.renewal-obligations.enabled'] = $true
    $rentalLocalConfiguration['storagehub.integration.renewal-payment-lifecycle.enabled'] = $true
}
$env:SPRING_APPLICATION_JSON = $rentalLocalConfiguration | ConvertTo-Json -Depth 4 -Compress
try {
    & $rentalLocalRunner
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend exited with an error. Check startup logs; this runner will not repair/reset the database.'
    }
} finally {
    $env:SPRING_APPLICATION_JSON = $null
}
