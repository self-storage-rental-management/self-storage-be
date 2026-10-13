# Payment API contract

## Phạm vi

Payment là nguồn xác nhận tiền thanh toán. Reservation và Renewal dùng chung `VnpayService`, merchant `app.vnpay`, chữ ký HMAC, endpoint Return/IPN và bảng Payment. Ledger ghi nghĩa vụ và phân bổ tiền; Renewal quyết định điều kiện ký/hoàn tất. Redirect từ trình duyệt không phải bằng chứng thanh toán thành công.

Từ 13/10/2026, báo giá Renewal mới chia tiền thuê sau giảm giá giống đặt kho: 40% trả trước qua VNPay, 60% thu tiền mặt khi ký, có chứng từ do Staff ghi nhận. Không thu lại cọc bảo đảm của Rental. Số tiền gateway lấy từ snapshot đã chấp nhận; hồ sơ cũ có cọc bằng 0 vẫn trả toàn kỳ. Chi tiết nghĩa vụ, chứng từ và hoàn tất xem [Renewal contract](RENEWAL_API_CONTRACT.md).

## API

Base path `/api`, envelope và lỗi theo [quy ước chung](BE-00_API_CONTRACT.md).

| Method/path | Mục đích |
|---|---|
| POST `/api/customer/reservations/{reservationId}/vnpay/payment-url` | Tạo URL trả cọc đặt kho của chính Customer |
| POST `/api/customer/renewals/{renewalId}/payment-intents` | Tạo URL trả cọc gia hạn của chính Customer; hồ sơ cũ không cọc trả toàn kỳ |
| GET `/api/customer/renewals/{renewalId}/payment` | Đọc trạng thái Payment gia hạn từ backend |
| GET `/api/public/payments/vnpay/return` | Xác minh callback rồi chuyển về frontend |
| GET `/api/public/payments/vnpay/ipn` | Xác minh callback và trả mã phản hồi VNPay |
| POST `/api/manager/payments/{paymentId}/vnpay/query` | Đối soát trạng thái gateway theo quyền và scope |
| POST `/api/manager/payments/{paymentId}/vnpay/refund` | Yêu cầu hoàn tiền theo quyền, scope và điều kiện Payment |

Hai API tạo URL yêu cầu Bearer và `Idempotency-Key`. Server quyết định số tiền, tiền tệ và transaction reference; client không gửi số tiền thay thế. Hợp đồng yêu cầu/revision, thời hạn, ký và kích hoạt Renewal được định nghĩa duy nhất tại [Renewal contract](RENEWAL_API_CONTRACT.md).

Renewal yêu cầu Customer chọn lịch ký trước khi mở intent trả cọc. BE kiểm tra và giữ chỗ lịch đã chọn trong transaction tạo intent; thời hạn trả cọc kết thúc trước lịch ký. Chọn lịch không phải bằng chứng đã trả tiền.

## Xác nhận và phân bổ

Return/IPN dùng cùng bước xác minh merchant, checksum, amount, transaction reference và thời điểm thanh toán. Callback lặp không được tạo thêm khoản thanh toán hoặc ghi đè metadata của giao dịch đã xác minh. Payment gia hạn ràng buộc với đúng Renewal và accepted revision; tiền đã nhận được lưu trước bước phân bổ Ledger và chuẩn bị ký. Lỗi các bước sau phải giữ bằng chứng nhận tiền và chuyển sang đối soát.

Body yêu cầu refund gồm `amount` và `reason`; không tự suy ra quyền hoàn tiền từ trạng thái Renewal.

## Cấu hình local dùng chung

Runner `-EnableRenewalPayments` hiện bật `storagehub.integration.renewal-simple-payment.enabled=true`: mở payment theo kiểm tra DB cục bộ đã được chấp nhận, không chờ đầy đủ financial coverage/Recovery/calendar/hold. Chi tiết và giới hạn đọc ở mục quy tắc hiện hành của Renewal contract. Các bước chuẩn bị ký và hoàn tất vẫn có điều kiện riêng; nhận tiền không tự gia hạn Rental.

Local launcher: `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-payment-dev.ps1`.
It loads `.local/vnpay.json` (Git ignored), enables the shared Payment VNPay Sandbox configuration and the Renewal payment lifecycle through `run-rental-support-dev.ps1`. Copy `docs/vnpay-local.example.json` and enter the merchant values locally; never commit the populated file. Restart the backend to load changed credentials. The default launcher does not enable the new Staff signing-calendar migration.

For the Sandbox test credentials supplied by the team, choose domestic card/bank account → NCB. Other payment categories require their own test accounts/cards. Configuration does not itself prove that VNPay accepted the merchant or that a transaction completed; inspect the verified shared Payment response after callback. Renewal financial/hold/Recovery prerequisites still apply.

- `STORAGEHUB_VNPAY_ENABLED`
- `STORAGEHUB_VNPAY_TMN_CODE`
- `STORAGEHUB_VNPAY_HASH_SECRET`
- `STORAGEHUB_VNPAY_PAY_URL`, `STORAGEHUB_VNPAY_API_URL`
- `STORAGEHUB_VNPAY_RETURN_URL`, `STORAGEHUB_VNPAY_FRONTEND_RETURN_URL`, `STORAGEHUB_VNPAY_IPN_URL`

Giá trị merchant phải lấy từ cấu hình Payment thực tế; không có trong thư mục mã controller/DTO. Không ghi credentials vào tài liệu hoặc Git. [Cấu hình JSON mẫu](vnpay-local.example.json) chỉ chứa placeholder, không gọi được gateway.

Sau khi nạp merchant vào terminal backend, bật các adapter local theo lệnh:

```powershell
.\run-rental-support-dev.ps1 -EnableRenewalPayments
```

Hoặc dùng file local được Git bỏ qua:

```powershell
.\run-rental-support-dev.ps1 -EnableRenewalPayments -VnpayConfigPath .\.local\vnpay.json
```

Renewal local hiện giới hạn ở VNPay Sandbox. Cấu hình cổng không thay thế bằng chứng tài chính: thiếu `LedgerCoverageSource` nhất quán hoặc nguồn cần thiết thì payment readiness vẫn `DEFERRED_SOURCE`. Repo hiện chưa có runtime implementation chứng nhận toàn bộ coverage tài chính lịch sử. Không coi Ledger rỗng hoặc deposit đã trả là không còn nợ.

Browser Return có thể về localhost. IPN từ server VNPay cần URL HTTPS công khai trỏ đến backend. Không có tunnel thì không xác nhận được IPN end-to-end trên localhost.

Schema, migration bất biến và rollout theo [PAYMENT_GATEWAY_MIGRATION.md](PAYMENT_GATEWAY_MIGRATION.md). Đây là hướng dẫn triển khai, không phải contract API thứ hai.
