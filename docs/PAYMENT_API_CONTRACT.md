# Payment API contract

## Phạm vi

Payment là nguồn xác nhận tiền thanh toán. Reservation và Renewal dùng chung `VnpayService`, merchant `app.vnpay`, chữ ký HMAC, endpoint Return/IPN và bảng Payment. Ledger ghi nghĩa vụ và phân bổ tiền; Renewal quyết định điều kiện ký/hoàn tất. Redirect từ trình duyệt không phải bằng chứng thanh toán thành công.

## API

Base path `/api`, envelope và lỗi theo [quy ước chung](BE-00_API_CONTRACT.md).

| Method/path | Mục đích |
|---|---|
| POST `/api/customer/reservations/{reservationId}/vnpay/payment-url` | Tạo URL trả cọc đặt kho của chính Customer |
| POST `/api/customer/renewals/{renewalId}/payment-intents` | Tạo URL trả toàn kỳ gia hạn của chính Customer |
| GET `/api/customer/renewals/{renewalId}/payment` | Đọc trạng thái Payment gia hạn từ backend |
| GET `/api/public/payments/vnpay/return` | Xác minh callback rồi chuyển về frontend |
| GET `/api/public/payments/vnpay/ipn` | Xác minh callback và trả mã phản hồi VNPay |
| POST `/api/manager/payments/{paymentId}/vnpay/query` | Đối soát trạng thái gateway theo quyền và scope |
| POST `/api/manager/payments/{paymentId}/vnpay/refund` | Yêu cầu hoàn tiền theo quyền, scope và điều kiện Payment |

Hai API tạo URL yêu cầu Bearer và `Idempotency-Key`. Server quyết định số tiền, tiền tệ và transaction reference; client không gửi số tiền thay thế. Hợp đồng yêu cầu/revision, thời hạn, ký và kích hoạt Renewal được định nghĩa duy nhất tại [Renewal contract](RENEWAL_API_CONTRACT.md).

## Xác nhận và phân bổ

Return/IPN dùng cùng bước xác minh merchant, checksum, amount, transaction reference và thời điểm thanh toán. Callback lặp không được tạo thêm khoản thanh toán hoặc ghi đè metadata của giao dịch đã xác minh. Payment gia hạn ràng buộc với đúng Renewal và accepted revision; tiền đã nhận được lưu trước bước phân bổ Ledger và chuẩn bị ký. Lỗi các bước sau phải giữ bằng chứng nhận tiền và chuyển sang đối soát.

Body yêu cầu refund gồm `amount` và `reason`; không tự suy ra quyền hoàn tiền từ trạng thái Renewal.

## Cấu hình local dùng chung

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
