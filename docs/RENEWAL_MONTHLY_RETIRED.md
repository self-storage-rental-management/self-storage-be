# Ngừng tạo thanh toán gia hạn từng tháng

## Hành vi mới

- Options chỉ công bố `FULL_TERM`. Đây là trả trước 20% tổng tiền thuê kỳ gia hạn, phần còn lại thu khi ký; không phải trả trước 100%.
- `MONTHLY` bị từ chối khi tạo quote, gửi/sửa yêu cầu bằng quote cũ, mở thanh toán, thu phần còn lại hoặc hoàn tất gia hạn. Muốn tiếp tục cần báo giá và xác nhận mới; không tự chuyển điều khoản đã chấp nhận.
- Gỡ công thức tạo giá/cọc theo tháng trong workflow tạo điều khoản mới.
- Gỡ các endpoint tạo mới:
  - `POST /api/{role:customer|staff}/renewals/{id}/installment-payment-quotes`
  - `POST /api/customer/renewals/{id}/installment-payment-intents`
  - `POST /api/staff/renewals/{id}/installment-receipts`
- Xóa DTO `RenewalInstallmentCommands`, phương thức tạo VNPay URL từng kỳ, các phương thức tạo quote/intent/receipt theo kỳ và `RenewalInstallmentNoticeScheduler`.

## Phần tương thích giữ lại

- Enum `MONTHLY` được đánh dấu deprecated để giải mã snapshot đã lưu. Đơn giá/tháng và gói thuê một tháng vẫn hợp lệ, không đồng nghĩa thanh toán từng tháng.
- Giữ các GET lịch sử payment/schedule/receipt, bảng DB và dữ liệu cũ.
- Giữ callback, xác minh nguồn tiền, đối soát/phân bổ giao dịch đã mở trước đây, xử lý hết hạn giao dịch và bằng chứng công nợ đã tồn tại. Không tạo payment intent mới hay URL thanh toán mới theo kỳ.
- Giữ phép đọc/kiểm chứng điều khoản tài chính cũ cho ledger và overdue; không sửa snapshot hoặc xóa khoản đã thu.
- Không đổi các nhánh xử lý sự cố/hoàn tiền thành hoàn tất gia hạn tự động.

## File thay đổi

Các đường dẫn dưới đây tính từ `src/main/java/com/storagehub/`:

- `api/renewal/RenewalPaymentPlan.java`, `RenewalCommands.java`, `RenewalInstallmentController.java`.
- Xóa `api/renewal/RenewalInstallmentCommands.java`.
- `service/renewal/RenewalWorkflowService.java`, `service/renewal/operations/RenewalOperationService.java`.
- `service/VnpayService.java`, `service/ledger/RenewalInstallmentService.java`, `RenewalLedgerAccountingConfiguration.java`.
- Xóa `service/ledger/RenewalInstallmentNoticeScheduler.java`.

Không thêm migration, không sửa dữ liệu DB. FE hiện không mở lựa chọn trả từng tháng nên không cần sửa giao diện cho thay đổi này.

## Kiểm tra bàn giao

Không chạy test theo yêu cầu. Biên dịch BE bằng `mvnw.cmd -B -Dmaven.test.skip=true package`. Chưa xác minh runtime với DB/gateway.

Khi QA: kiểm tra MONTHLY bị từ chối ở quote và các lệnh mới dùng quote cũ; ba POST đã gỡ không còn handler; FULL_TERM vẫn trả trước 20% và thu phần còn lại khi ký; GET lịch sử và callback của payment cũ vẫn dùng chứng cứ gốc, không thu trùng.
