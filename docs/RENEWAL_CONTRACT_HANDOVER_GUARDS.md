# Hợp đồng gia hạn bắt buộc và xác nhận timeline bàn giao

Cập nhật 26/10/2026. Không chạy test theo yêu cầu.

## Hoàn tất gia hạn

- DTO Completion bắt buộc signedDocumentFileId. Service cũng từ chối null, để không phụ thuộc riêng validation của HTTP controller.
- File tiếp tục đi qua cơ chế SIGNED_CONTRACT hiện có: quyền người dùng, liên kết đúng renewal, trạng thái file và kiểm tra file thực tế. Không thêm bước Manager duyệt tài liệu.
- FE khai báo file bắt buộc trong command và kiểm tra UUID trước khi gửi. Form hiện tại vẫn yêu cầu upload.
- Đủ tiền, lịch ký/arrival, hạn ký/thu hồi, quyền giữ kho và ngày hiệu lực vẫn được kiểm tra như trước. Upload file riêng lẻ không tự hoàn tất gia hạn.
- Giữ nguyên quy tắc: chưa quá hạn nối tiếp kỳ cũ; overdue bắt đầu từ ngày hoàn tất ký đủ điều kiện; không cộng setup.

## Xác nhận nhận kho ban đầu

- confirmReceipt không tạo Rental nếu không tìm thấy. Không tạo hợp đồng SIGNED hoặc mở ledger thay cho dữ liệu bàn giao bị thiếu.
- Chỉ dùng Rental đã tồn tại, khóa bản ghi để kiểm tra. CheckIn phải hoàn tất và liên kết đúng Rental/reservation/customer/facility/unit.
- Bắt buộc có các mốc thực tế: ký hợp đồng, thu tiền, bắt đầu bàn giao/setup, kết thúc setup và kiểm kê. Kiểm tra thời gian không ở tương lai hoặc sai thứ tự.
- Ngày bắt đầu tính tiền và ngày kết thúc phải khớp timeline bàn giao/setup cùng số ngày setup, số tháng đã lưu. Không lấy ngày reservation làm fallback và không sửa ngày Rental tại bước xác nhận.
- Sau xác nhận, lưu chứng cứ CUSTOMER_HANDOVER_PERIOD_V2 gắn với CheckIn và timeline thực tế; chốt kỳ thuê với quy ước kết thúc exclusive của luồng startSetup hiện hành. Không ghi rằng Rental vừa được tạo.
- Đọc chứng cứ V1 cũ được giữ nguyên. Gửi lại xác nhận cho reservation đã COMPLETED vẫn là đọc kết quả, không tạo side effect lần hai.
- Hồ sơ thiếu hoặc lệch chứng cứ bị chặn để đối soát; không tự backfill ngày hoặc tự tạo hợp đồng thay thế.

## Kiểm chứng và giới hạn

Đã kiểm tra FE typecheck và BE build bỏ qua toàn bộ test. Chưa xác minh bằng thao tác trình duyệt, DB hoặc gateway thực tế. Cần khởi động lại BE để áp dụng. Chưa commit/push.
