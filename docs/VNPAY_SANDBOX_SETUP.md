# VNPay Sandbox setup

Keep all merchant credentials outside source control. Never commit the terminal code or hash secret.

## Local payment and browser return

Set these variables in the same PowerShell window that starts the backend:

```powershell
$env:STORAGEHUB_VNPAY_ENABLED="true"
$env:STORAGEHUB_VNPAY_TMN_CODE="YOUR_SANDBOX_TMN_CODE"
$env:STORAGEHUB_VNPAY_HASH_SECRET="YOUR_SANDBOX_HASH_SECRET"
$env:STORAGEHUB_VNPAY_PAY_URL="https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"
$env:STORAGEHUB_VNPAY_API_URL="https://sandbox.vnpayment.vn/merchant_webapi/api/transaction"
$env:STORAGEHUB_VNPAY_RETURN_URL="http://localhost:8080/api/public/payments/vnpay/return"
$env:STORAGEHUB_VNPAY_FRONTEND_RETURN_URL="http://localhost:8443/"
```

The browser can return to localhost. The backend validates the VNPay checksum and then redirects to the frontend.

## IPN during local development

VNPay servers cannot call localhost. Expose port 8080 with an HTTPS tunnel and register this URL in the VNPay Sandbox merchant configuration:

```text
https://YOUR-TUNNEL.example/api/public/payments/vnpay/ipn
```

Without a tunnel, browser Return and manager QueryDR can still be tested. Production must use a public HTTPS IPN URL.

## Endpoints

- Customer creates a deposit payment URL: `POST /api/customer/reservations/{reservationId}/vnpay/payment-url`
- Browser return: `GET /api/public/payments/vnpay/return`
- VNPay IPN: `GET /api/public/payments/vnpay/ipn`
- Manager reconciliation: `POST /api/manager/payments/{paymentId}/vnpay/query`
- Manager refund: `POST /api/manager/payments/{paymentId}/vnpay/refund`

Refund body:

```json
{
  "amount": 100000,
  "reason": "Customer-approved deposit refund"
}
```
