# Shared Payment and Renewal VNPay Sandbox launcher. Merchant stays in ignored local JSON.
param([string]$VnpayConfigPath = (Join-Path $PSScriptRoot '.local/vnpay.json'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $VnpayConfigPath -PathType Leaf)) {
    throw 'Missing local VNPay JSON. Copy docs/vnpay-local.example.json to .local/vnpay.json and configure your Sandbox merchant.'
}
& (Join-Path $PSScriptRoot 'run-rental-support-dev.ps1') -EnableRenewalPayments -VnpayConfigPath $VnpayConfigPath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
