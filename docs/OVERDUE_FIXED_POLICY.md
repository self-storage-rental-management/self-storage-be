# Chính sách quá hạn và thu hồi cố định

Ngày cập nhật: 19/10/2026.

## Các mốc vận hành

Áp dụng cho quá hạn thời hạn thuê, tính từ sau ngày cuối cùng được phép sử dụng kho, theo ngày lịch và múi giờ Việt Nam.

| Mốc | Xử lý |
|---|---|
| Ngày 1–3 | Nhắc khách gia hạn hoặc trả kho |
| Ngày 4–6 | Cảnh báo quá hạn nghiêm trọng |
| Ngày 7 | Cảnh báo khẩn cấp |
| 17:00 ngày 7 | Hạn cuối hoàn tất gia hạn trước khi chuyển thu hồi |
| Từ 08:00 ngày 8 | Đủ mốc thời gian chuyển sang xử lý thu hồi, vẫn phải thỏa các kiểm tra an toàn hiện có |

Thời hạn ký riêng của hồ sơ gia hạn vẫn áp dụng như trước. Hạn cuối trước thu hồi không có nghĩa là mọi hồ sơ đều được kéo dài hạn ký đến ngày 7.

## Cấu hình được làm gọn

- Trang chính sách hiển thị các mốc dưới dạng thông tin cố định, không còn công tắc bật/tắt hoặc ô nhập mức cảnh báo, ngày thu hồi và giờ xử lý.
- Nguồn chung của BE là `RenewalOverdueTimeline.policy()`. Tác vụ thời gian và API đọc/xuất bản chính sách dùng cùng quy tắc.
- FE không gửi hoặc giữ bản sao mốc ngày/giờ mặc định. Trang chính sách lấy các mốc trực tiếp từ trường `term` của API; khi tạo hoặc lưu chính sách, BE tự bổ sung các mốc chuẩn.
- Với từng hồ sơ, BE tính ngày giờ cụ thể từ ngày cuối được phép sử dụng kho và `Asia/Ho_Chi_Minh`. API `renewal-risk` trả `repossessionDeadline` và `repossessionStartsAt`; FE chỉ định dạng các giá trị này theo giờ Việt Nam để hiển thị, không cộng ngày hoặc tính lại hạn thu hồi.
- Chính sách đã lưu được đọc với các mốc cố định; không sửa trực tiếp JSON lịch sử hoặc phiên bản trong database. Khi BO lưu chỉnh sửa, chính sách mới lưu các mốc chuẩn.
- Các trường `term` cũ vẫn được nhận để tương thích với bên tích hợp; không còn là các lựa chọn tùy chỉnh mốc vận hành. Nguồn đọc và kết quả xuất bản trả quy tắc cố định.
- Chính sách cơ sở đã xuất bản vẫn là điều kiện để luồng gia hạn hoạt động; thay đổi này không tự tạo chính sách cho cơ sở chưa có.

## Phạm vi được giữ

- Không đổi thời hạn ký, quy trình xác minh thanh toán hoặc quy tắc xử lý tiền.
- Không tự chuyển nợ tiền tháng thành thu hồi khi thời hạn thuê vẫn còn.
- Tạo nhiệm vụ thu hồi không tự coi kho đã trống hoặc chuyển kho sang trạng thái có thể cho thuê.
- Kiểm kê, chứng từ, đối soát, xử lý tài sản và điều kiện giải phóng kho vẫn theo luồng hiện có.
- Không thêm bước quản lý duyệt gia hạn, không sửa các màn hình vận hành nhiệm vụ trong lượt làm gọn cấu hình này.

## Triển khai

- Không có migration hoặc thay đổi dữ liệu database.
- Khởi động lại BE để nhận quy tắc đọc/xuất bản chính sách mới; tải lại FE để nhận giao diện.
- Không chạy kiểm thử tự động trong lượt sửa này.
- Biên dịch BE và kiểm tra kiểu dữ liệu FE: thành công.
