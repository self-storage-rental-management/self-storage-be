# Nguồn công khai cho trợ lý StorageHub

## Nguồn duy nhất

`self-storage-be/src/main/resources/ai/support-public-knowledge.json` là bộ hướng dẫn được công bố cùng phiên bản phần mềm. Mỗi mục có id, câu hỏi, câu trả lời, các cách hỏi tương đương đã duyệt và tham chiếu tới chức năng/API công khai hoặc dành cho Customer.

Khách có thể đọc toàn bộ bộ hướng dẫn qua `GET /api/public/support/knowledge` không cần quyền quản trị. Endpoint này chỉ công bố hướng dẫn, không chứa hồ sơ hoặc số liệu riêng của khách. Việc liệt kê đường dẫn Customer trong nguồn không cấp quyền truy cập dữ liệu; endpoint tương ứng vẫn kiểm tra đăng nhập và quyền sở hữu như trước.

Nguồn đối chiếu khi biên soạn:

| Nội dung khách được biết | Chức năng/nguồn chính thức |
| --- | --- |
| Cơ sở, loại kho | PublicFacilityController, PublicUnitTypeController; chỉ hướng dẫn xem danh mục đang công bố |
| Gia hạn và kỳ hiệu lực | CustomerRenewalController, RenewalWorkflowService, RenewalOperationService; chỉ mô tả quy trình Customer |
| Thanh toán, đối soát | CustomerReservationController payment-history, chi tiết gia hạn, RenewalTransactionReader; không suy ra thành công từ ảnh/reference |
| Quá hạn | Hồ sơ thuê/yêu cầu gia hạn mà Customer có quyền xem; không công bố cấu hình nội bộ hoặc tự báo phí |
| Trả kho/hoàn tiền | Chức năng yêu cầu trả kho của Customer và quyết toán; không cam kết số tiền hoặc thời gian hoàn |
| Hỗ trợ | CustomerSupportController, chỉ kênh/tin nhắn công khai; không lấy internal notes, SLA nội bộ hoặc danh sách phân công |
| Khôi phục mật khẩu | AuthController và màn hình đăng nhập; không lấy PIN, OTP hoặc mật khẩu |

Tên file/lớp ở bảng này dùng cho người bảo trì đối chiếu; không nạp tài liệu nội bộ này vào model.

## Quy tắc trả lời

- FAQ exact match và Gemini cùng dùng một catalog. Bot legacy tải catalog công khai; FE chỉ giữ tên chủ đề, không giữ câu trả lời tài chính riêng.
- Gemini chỉ chọn `sourceId` phù hợp với câu hỏi hiện tại. Backend chỉ trả nguyên văn câu trả lời đã duyệt của mục đó; không trả văn bản model tự sinh.
- Ngoài phạm vi, thiếu nguồn, không rõ ý, hỏi nhiều phần mà nguồn không đủ, hỏi hồ sơ riêng hoặc yêu cầu thao tác: chọn UNKNOWN, trả lời không biết và đề xuất Nhân viên.
- Kết quả model sai định dạng, không đúng id, bị cắt hoặc chứa nội dung tự viết: trả UNKNOWN. Chat service kiểm tra lại nội dung theo allowlist trước khi trả khách.
- Không gửi lịch sử chat do client cung cấp cho model. Không coi tin nhắn assistant cũ là bằng chứng nghiệp vụ. Không gọi URL hoặc công cụ do người hỏi đưa vào.
- Không nạp SystemSetting/lateFeeAmount, cấu hình BO, bản nháp chính sách, dữ liệu khách khác, ghi chú nội bộ hoặc tài liệu repo vào prompt.
- Chưa đọc giá/còn kho thời gian thực: hướng khách xem danh mục chính thức; không tự báo một con số. Chưa đọc hồ sơ thực tế: nói rõ không xác nhận được.
- Bộ chọn bằng model vẫn có thể chọn nhầm chủ đề. Giới hạn nội dung bằng allowlist ngăn model đưa thêm kiến thức ngoài nguồn; không thay thế việc rà soát chất lượng phân loại khi vận hành.

Đã loại khỏi nguồn bot các khẳng định không được xác nhận: tự gạch nợ sau 30 giây, hỗ trợ thanh toán định kỳ, phí trễ bằng 50% đơn giá ngày, PIN tự đổi lúc nửa đêm, hoàn cọc trong 12 giờ, tự chuyển người hoặc bảo đảm có người đến ngay.

## Khi cập nhật

Chỉ bổ sung nội dung khách được biết sau khi đối chiếu chức năng/API hiện hành. Dữ liệu định lượng thay đổi theo cấu hình chỉ được đưa vào sau khi có nguồn công bố phù hợp cho khách, không sao chép mặc định trong code. Tăng version catalog và bộ đọc FE cùng lúc. Khởi động lại BE để nạp nguồn mới; câu trả lời cũ trong lịch sử không được coi là chính sách hiện hành.

Không chạy test theo yêu cầu. Chỉ kiểm tra FE typecheck và BE build bỏ qua test; chưa gọi Gemini thật hoặc xác minh endpoint trên tiến trình đang chạy.
