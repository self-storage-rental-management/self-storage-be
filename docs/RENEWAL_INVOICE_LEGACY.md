# Bảng kê gia hạn và dữ liệu invoice legacy

Cập nhật 26/10/2026. Phạm vi: renewal/overdue liên quan, không xây hệ thống phát hành hóa đơn mới.

## Nguồn chính thức

- Số tiền và trạng thái khoản phải thu lấy từ `statusSummary.invoice` do backend đối soát từ điều khoản/ledger.
- Hiển thị là **Bảng kê khoản phải thu gia hạn**. Bảng kê không chứng nhận đã phát hành hóa đơn.
- Biên lai thuộc từng giao dịch; giữ điều kiện `receiptAvailable` của backend. Không tạo quyền xem biên lai bằng trạng thái local PAID hoặc ngày ký.
- Thiếu summary/không đủ dữ liệu: hiển thị chưa xác định/cần đối soát; không tự tính tổng hoặc lấy trạng thái yêu cầu làm trạng thái đã thanh toán.

## Các thay đổi legacy

- Gỡ sinh mã INV-RNW, ngày phát hành và hạn thanh toán tại context FE.
- Các lệnh local request/edit/approve/reject/cancel/expire/pay/complete renewal không còn ghi trạng thái hoặc chứng từ tài chính; chúng báo cần xử lý qua flow API. Không biến mã legacy thành ID backend.
- Gỡ effect Customer tự hết hạn yêu cầu theo đồng hồ FE. Các bản ghi cũ không bị tự đổi invoice thành mất hiệu lực hoặc suy ra mất khoản trả trước khi hủy.
- Thay các nút chỉnh sửa/thanh toán/hủy renewal local bằng xem dữ liệu đã lưu.
- Giữ mã chứng từ, trạng thái, ngày, số tiền và tệp hợp đồng cũ để xem lịch sử; ghi rõ chưa đối soát. Ngày ký cũ không còn được gắn nhãn thanh toán hoàn tất.
- Bản ghi payment renewal local hiển thị dưới dạng dữ liệu lịch sử, không dựng biên lai đã xác minh. Lịch sử Manager đánh dấu chưa đối soát; tổng đã thu không được xác nhận nếu có khoản renewal legacy.
- Bố cục biên lai API và nghiệp vụ thu tiền mặt check-in không thay đổi.

## Kiểm chứng

FE typecheck và kiểm tra diff; không chạy test theo yêu cầu. Chưa kiểm chứng trong trình duyệt hoặc thao tác với DB/gateway. Chưa commit/push thay đổi này. Các bản ghi legacy vẫn được giữ trong dữ liệu cũ; chưa có chuyển đổi sang hệ thống phát hành hóa đơn chính thức.
