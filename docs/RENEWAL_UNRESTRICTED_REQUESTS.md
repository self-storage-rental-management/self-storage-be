# Gia hạn không giới hạn thời điểm gửi và không có hạn báo giá

Ngày thay đổi: 19/10/2026.

## Quy tắc hiện hành

- Khách có thể gửi yêu cầu gia hạn bất kỳ lúc nào khi hồ sơ còn đáp ứng điều kiện gia hạn. Không còn giới hạn chỉ được gửi trước ngày hết thuê một số ngày nhất định và không phụ thuộc công tắc chế độ demo.
- Báo giá gia hạn không có thời hạn sử dụng. Báo giá mới trả `expiresAt: null`.
- Khi gửi hoặc sửa yêu cầu, BE vẫn xác minh chủ sở hữu, trạng thái hồ sơ, chính sách, gói, giá và nội dung báo giá hiện hành. Báo giá không hết hạn theo thời gian không có nghĩa là được bỏ qua thay đổi giá/chính sách hoặc tình trạng hồ sơ.
- Báo giá cũ có `expiresAt` vẫn giữ dấu vết thời hạn đã lưu, nhưng trường này không còn được dùng để chặn chấp nhận hoặc xác minh phân bổ tiền gia hạn.
- Không sửa nội dung, giá, hash hoặc thời điểm chấp nhận của điều khoản đã lưu.

## Giao diện chính sách

Đã bỏ các trường:

- Cho phép gửi yêu cầu gia hạn bất kỳ lúc nào (chế độ demo).
- Số ngày được gửi yêu cầu trước khi hết thuê.
- Thời hạn báo giá.

Giao diện khách không còn hiển thị thời hạn báo giá hoặc vô hiệu hóa gửi yêu cầu do báo giá hết hạn.

## Tương thích

- `quoteTtlMinutes`, `quoteTtlHours` và `requestWindowDays` là các trường cũ, không còn điều khiển nghiệp vụ. API vẫn nhận chúng để tương thích; yêu cầu xuất bản chính sách mới không cần gửi chúng. Chính sách xuất bản mới lưu giá trị tương thích bằng 0.
- Các giới hạn giao dịch thanh toán, hạn ký, điều kiện hồ sơ, công nợ và mốc chuyển thu hồi vẫn được xử lý theo luồng riêng. Báo giá thanh toán các kỳ sau và bảng kê thu tiền không thuộc báo giá chọn gói gia hạn bị bỏ hạn trong thay đổi này.
- Luồng đặt kho ban đầu không thuộc phạm vi thay đổi.

## Database và triển khai

- Migration `V2026101902__renewal_quotes_without_expiry.sql` cho phép `renewal_quotes.expires_at` nhận NULL, không xóa dữ liệu hoặc sửa giá trị lịch sử.
- Cần áp dụng migration và khởi động lại BE để chạy code mới. FE cần tải lại nếu đang giữ biểu mẫu cũ.
- Không chạy kiểm thử tự động trong lượt sửa này.
- Biên dịch BE và kiểm tra kiểu dữ liệu FE: thành công.
