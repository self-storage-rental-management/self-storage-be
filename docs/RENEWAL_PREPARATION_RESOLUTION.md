# Xử lý khoản gia hạn đã thu nhưng chưa chuẩn bị được ký

Phạm vi: renewal và nhánh overdue liên quan. Không thay đổi đặt mới hoặc bàn giao ban đầu.

## Điều kiện và luồng xử lý

1. Backend chỉ mở nhánh này khi khoản thanh toán đã xác minh, đã phân bổ vào sổ cái và có biên nhận, nhưng chưa chuẩn bị được ký gia hạn. Ảnh chuyển khoản hoặc mã tham chiếu riêng lẻ không đủ điều kiện.
2. Worker thử lại tối đa 6 lần trong một lượt, trong thời hạn ký ban đầu. Khi hết số lần hoặc hết thời hạn, chuyển sang chờ cơ sở xử lý, ngừng thử tự động và thông báo cho cơ sở/khách.
3. Manager có quyền phù hợp chọn một trong hai cách, kèm lý do và chứng cứ liên quan:
   - Thử chuẩn bị lại: chỉ khi còn thời hạn ký và chưa đến mốc thu hồi, chưa duyệt hoàn. Không thu thêm tiền, không kéo dài hạn ký hay cộng ngày setup.
   - Duyệt hoàn khoản đã thu: cần xác nhận không thể tiếp tục cung cấp dịch vụ. Backend xác minh lại tiền thực thu, số đã hoàn, điều khoản đã chấp nhận và sổ cái; tính số còn được hoàn. Yêu cầu gia hạn được đóng và nghĩa vụ tiền thuê chưa phát sinh được hủy theo luồng kế toán hiện có.
4. Staff khác người duyệt thực hiện khoản hoàn và lưu chứng từ. Hàng chờ Staff bao gồm khoản hoàn đã duyệt dù chưa có lịch ký. Chỉ bước ghi nhận hoàn thực tế mới cập nhật số đã hoàn.

## Các chốt bảo vệ

- Không tạo giả trạng thái đã ký, lịch hẹn hoặc xác nhận thanh toán trong operation state để mở quyền hoàn.
- Lỗi chuẩn bị của hệ thống không tự biến thành khách không đến hoặc mất khoản trả trước.
- Payment và invoice vẫn lấy trạng thái từ các nguồn tài chính hiện hành; trạng thái chờ chuẩn bị thuộc workflow.
- Lệnh có khóa dữ liệu, kiểm tra phiên bản và Idempotency-Key; chặn hoàn trùng, thử lại sau khi duyệt hoàn và xử lý khi còn giao dịch chưa đối soát.
- Thu hồi quá hạn chỉ tiếp tục khi khoản đã thu được xác minh đầy đủ. Yêu cầu bị đóng bởi thu hồi vẫn có đường duyệt hoàn khoản này; không tự tạo mất tiền do no-show.
- Không cộng setup, không cập nhật thời hạn hợp đồng từ ngày thanh toán.

## Giới hạn kiểm chứng

Thay đổi được kiểm tra bằng FE typecheck và BE build bỏ qua test. Không chạy test theo yêu cầu; chưa xác nhận bằng giao dịch gateway/hoàn tiền thật hoặc thao tác trên dữ liệu dùng chung. Khởi động lại BE là cần thiết để tiến trình đang chạy nhận mã mới.
