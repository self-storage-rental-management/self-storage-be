# Chuẩn hóa contract trạng thái FE/BE

Ngày cập nhật: 26/10/2026. Phạm vi: mã trạng thái, kiểu dữ liệu, kiểm tra phản hồi và tài liệu API; không thay đổi tiền hoặc điều kiện chuyển bước nghiệp vụ.

## Nguồn định nghĩa

| Đối tượng | Nguồn backend | Ý nghĩa |
| --- | --- | --- |
| PaymentStatus | domain/model/PaymentStatus.java | Trạng thái bản ghi/thử thanh toán: PENDING, PROCESSING, PAID, NOT_RECEIVED, FAILED, CANCELLED |
| ReservationStatus | domain/model/ReservationStatus.java | Vòng đời đơn đặt kho; CONFIRMED không phải tên thay thế của PaymentStatus.PAID |
| RentalStatus | domain/model/RentalStatus.java | Vòng đời thuê, bao gồm setup |
| RenewalStatus | domain/model/RenewalStatus.java | Vòng đời yêu cầu gia hạn lưu trong DB; giữ các mã lịch sử |
| CheckInStatus | domain/model/CheckInStatus.java | Tiến trình check-in, độc lập reservation/payment |
| InvoiceStatus | api/StatusContract.java | NOT_ISSUED, UNPAID, PARTIALLY_PAID, PAID, CANCELLED, UNKNOWN |
| InvoiceRefundStatus | api/StatusContract.java | NONE, PENDING, PARTIALLY_REFUNDED, REFUNDED, UNKNOWN |
| PaymentTransactionStatus | api/StatusContract.java | Kết quả giao dịch đã đối soát: có SUCCEEDED, EXPIRED, REFUNDED và RECONCILIATION_REQUIRED; không đồng nhất với raw PaymentStatus |
| RenewalWorkflowStatus | api/StatusContract.java | Tiến độ xử lý hiện tại do projection backend xác định, độc lập trạng thái khoản phải thu/giao dịch |

Backend RenewalStatusSummary kiểm tra mã workflow/invoice/refund/transaction khi tạo DTO và công bố enum tương ứng qua schema OpenAPI. JSON giữ nguyên tên trường và mã hiện hành; không có migration hoặc đổi trạng thái dữ liệu lịch sử.

## FE dùng chung nguồn

`scripts/generate-status-contracts.mjs` đọc 9 enum backend và sinh `src/types/statusContracts.ts`, gồm danh sách mã, type và hàm kiểm tra runtime. Không khai báo lại các union này trong từng service.

Chạy từ thư mục FE:

```sh
npm run statuses:generate
npm run statuses:check
```

Mặc định backend ở thư mục kế bên `../self-storage-be`; có thể chỉ định đường dẫn repo qua `STORAGEHUB_BE_ROOT`. File đã sinh được commit trong FE nên build FE thông thường không yêu cầu checkout backend. Khi thay enum BE, cần sinh lại và chạy kiểm tra đồng bộ trong workspace có cả hai repo.

Các service customer reservation/payment, customer rental, staff reservation, rental, renewal, check-in và kết quả phân kho dùng type/validator từ contract chung. Mã không được định nghĩa trong contract bị báo lỗi phản hồi; không tự chuyển sang PAID, active hoặc CONFIRMED.

Các bảng nhãn renewal dùng `satisfies Record<...>` theo enum tương ứng, để typecheck phát hiện thiếu/thừa mã. Việc chọn trạng thái thành công và quyền xem biên lai vẫn thuộc projection/chứng cứ backend, không suy ra từ nhãn FE.

## Legacy

- `LegacyReservationStatus` mô tả vòng đời local/demo cũ, gồm các mã như DEPOSIT_PAID và awaiting_payment. Context, reservationFlow và model local Staff dùng tên legacy rõ ràng.
- `LegacyPaymentStatus` giữ các giá trị local lịch sử như paid, overdue và refunded. Đây là dữ liệu tổng hợp cũ, không được dùng làm contract Payment API hoặc chứng cứ thanh toán.
- Các export ReservationStatus, RentalStatus, PaymentStatus tại storageHub.ts trỏ về contract API chung. RentalStatus có setup.
- Không đổi hàng loạt localStorage hoặc suy ra một payment PAID từ DEPOSIT_PAID, overdue/refunded. Không thay đổi các nghiệp vụ tạo invoice legacy trong lần sửa contract này.

## Kiểm chứng và giới hạn

Đã đối chiếu file sinh với 9 enum backend; kiểm tra FE bằng typecheck và BE bằng Maven package bỏ qua toàn bộ test. Không chạy test theo yêu cầu. Chưa xác minh qua trình duyệt hoặc thao tác giao dịch thực tế. Các thay đổi không tự hoàn thiện những task khác như chặn cọc trước check-in, bỏ invoice legacy hoặc chính sách setup/refund.
