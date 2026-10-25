# Kịch bản happy path: đặt kho, trả kho, gia hạn và hỗ trợ

Tài liệu dùng để demo hoặc kiểm thử thủ công 4 flow chính. Mỗi flow đi theo trường hợp thành công thông thường, từ lúc bắt đầu đến khi hoàn tất.

## Chuẩn bị chung

- FE, BE và DB đang chạy; email xác minh, VNPay Sandbox và các nguồn nghiệp vụ cần thiết đã cấu hình.
- Có tài khoản Customer, Staff và Manager; nhân sự được cấp quyền tại đúng cơ sở.
- Cơ sở đang hoạt động, có gian trống, giá và gói thuê hợp lệ; chính sách gia hạn và hỗ trợ có hiệu lực.
- Chuẩn bị minh chứng thanh toán, hợp đồng đã ký và ảnh/biên bản kiểm kê theo biểu mẫu.
- Dùng hồ sơ riêng cho trả kho và gia hạn để hai flow không chặn nhau. Lấy số tiền từ báo giá/bảng kê của hệ thống.

## 1. Đặt kho và nhận kho

**Mục tiêu:** Khách đặt kho, thanh toán và nhận bàn giao thành công; hình thành hồ sơ thuê đang hoạt động.

**Vai trò:** Customer → Manager → Staff → Customer.

**Điều kiện:** Khách đã đăng nhập, hàng hóa hợp lệ và không cần duyệt thủ công, loại kho phù hợp còn khả dụng trong kỳ thuê.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Tìm cơ sở, chọn khoảng thuê và loại kho; khai báo số lượng, kích thước, khối lượng hàng hóa. | Hệ thống xác nhận hàng phù hợp và có kho khả dụng. |
| 2 | Customer | Chọn gói, lấy báo giá, kiểm tra thông tin khách/giấy tờ và xác nhận đặt kho. | Tạo đơn với kỳ thuê, cọc và các khoản cần thanh toán theo báo giá. |
| 3 | Customer | Xác minh email của đơn khi được yêu cầu; lưu lịch nhận kho theo các bước trên giao diện. | Đơn được xác minh và có lịch nhận kho hợp lệ. |
| 4 | Customer | Thanh toán cọc qua VNPay Sandbox, quay lại và đợi hệ thống xác thực kết quả. | Cọc được ghi nhận; đơn đủ điều kiện phân kho. |
| 5 | Manager | Vào Phân kho đặt chỗ, chọn đơn và phân một gian phù hợp được hệ thống cho phép. | Đơn có gian cụ thể, chuyển UNIT_RESERVED; gian được giữ cho đúng đơn. |
| 6 | Staff | Mở Nhận kho & bàn giao, kiểm tra lịch, chuẩn bị check-in và đối chiếu giấy tờ gốc, hàng thực tế khi khách đến. | Check-in sẵn sàng; khách và hàng đáp ứng điều kiện bàn giao. |
| 7 | Staff | Thu đúng khoản còn lại theo bảng kê nhận kho, lưu chứng từ; hoàn tất hợp đồng ký, cấp mã/thẻ/chìa khóa theo loại sử dụng. | Thanh toán nhận kho và credential được ghi nhận đúng hồ sơ. |
| 8 | Staff | Hoàn tất checklist, minh chứng và bàn giao. | Check-in hoàn tất; đơn chờ Customer xác nhận đã nhận kho. |
| 9 | Customer | Xác nhận đã nhận kho, mở Hồ sơ thuê. | Rental hoạt động, gian occupied; kỳ thuê và chứng từ đúng với đơn. |

**Kết thúc:** Khách đã có hồ sơ thuê đang hoạt động. Staff bàn giao xong chưa phải điểm kết thúc; cần Customer xác nhận nhận kho ở bước 9.

## 2. Trả kho và hoàn tiền bảo đảm

**Mục tiêu:** Khách trả kho, đồng ý quyết toán và nhận phần tiền bảo đảm còn lại; hồ sơ thuê kết thúc.

**Vai trò:** Customer → Staff → Customer → nhân sự có quyền hoàn tiền.

**Điều kiện:** Rental được phép trả kho; số dư và tiền bảo đảm đã được ghi nhận. Chọn trường hợp không có tranh chấp, gian đủ điều kiện cho thuê lại và số tiền được hoàn lớn hơn 0.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Mở Hồ sơ thuê, gửi yêu cầu trả kho với lịch/ngày và ghi chú hợp lệ. | Tạo hồ sơ trả kho gắn đúng Rental. |
| 2 | Staff | Mở yêu cầu, kiểm kê thực tế, ghi nhận hiện trạng, chìa khóa/thẻ/khóa đã trả, phí phát sinh thực tế và tải minh chứng. | Có biên bản kiểm kê và bảng quyết toán chờ khách xác nhận. |
| 3 | Customer | Xem biên bản, tiền bảo đảm, các khoản khấu trừ và tiền hoàn; chọn đồng ý. | Hồ sơ chuyển sang chờ hoàn tiền theo số tiền hệ thống tính. |
| 4 | Nhân sự có quyền | Thực hoàn đúng số tiền, ghi nhận phương thức và chứng từ theo biểu mẫu. | Quyết toán hoàn tất, có ghi nhận hoàn tiền. |
| 5 | Customer / Manager | Tải lại hồ sơ thuê, quyết toán và trạng thái gian kho. | Rental kết thúc; gian available theo kết quả kiểm kê, quyền truy cập được xử lý theo quy tắc trả kho. |

**Kết thúc:** Hồ sơ thuê đã đóng và hoàn tiền được ghi nhận. Số tiền hoàn là tiền bảo đảm trừ khấu trừ hợp lệ, không mặc định bằng toàn bộ tiền đã thanh toán.

## 3. Gia hạn và hoàn tất ký

**Mục tiêu:** Khách thanh toán đủ, ký gia hạn và được cập nhật kỳ thuê mới.

**Vai trò:** Customer → Staff. Manager có thể theo dõi; flow mới không cần Manager duyệt.

**Điều kiện:** Rental còn hiệu lực, trong khoảng được gửi yêu cầu gia hạn; gói được BO cho phép, không có trả kho, tranh chấp hoặc công nợ khác chặn luồng; có giờ hẹn khả dụng trong hạn ký.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Mở Rental, chọn gia hạn, gói thuê và phương án trả cả kỳ; lấy báo giá. | Hiển thị kỳ gia hạn, tổng tiền, cọc và phần còn lại. |
| 2 | Customer | Kiểm tra, xác nhận điều khoản và gửi yêu cầu. | Tạo hồ sơ gia hạn với điều khoản được chấp nhận; không chờ Manager duyệt. |
| 3 | Customer | Chọn và lưu giờ hẹn đến ký. | Có lịch hẹn hợp lệ được lưu. |
| 4 | Customer | Thanh toán cọc qua VNPay Sandbox và đợi xác thực, phân bổ. | Cọc được ghi nhận; hồ sơ chờ hoàn tất ký, xem được biên lai khi đủ điều kiện. |
| 5 | Staff | Khi khách đến, đối chiếu và ghi nhận khách đến theo đúng hồ sơ/lịch hiện hành. | Có lượt đến được ghi nhận. |
| 6 | Staff | Đọc bảng kê, thực thu phần còn lại bằng tiền mặt/chuyển khoản, tải minh chứng và xác nhận đã thu. | Ghi nhận đủ khoản cần thanh toán theo bảng kê. |
| 7 | Staff | Hoàn tất các xác nhận, tải hợp đồng đã ký và chọn hoàn tất gia hạn. | Gia hạn completed, Rental được cập nhật kỳ thuê mới đúng một lần. |
| 8 | Customer / Manager | Tải lại Rental, hợp đồng và lịch sử thanh toán. | Thời hạn mới và chứng từ khớp; gian tiếp tục occupied. |

**Kết thúc:** Gia hạn hoàn tất. Thanh toán cọc chưa tự đổi thời hạn thuê; khung phân bổ lịch 30 phút không phải hạn cuối hoàn tất ký của khách.

## 4. Hỗ trợ khách hàng

**Mục tiêu:** Khách bắt đầu chat, Staff tiếp nhận và giải đáp, khách xác nhận kết quả.

**Vai trò:** Customer → Staff → Customer. Manager theo dõi nếu cần.

**Điều kiện:** Staff có quyền tại đúng cơ sở; chính sách cho phép khách xác nhận đóng, còn trong khoảng thời gian xem xét kết quả.

**Tình huống mẫu:** Khách cần hướng dẫn xem biên lai gia hạn.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Chọn Cuộc trò chuyện mới, chọn cơ sở/hồ sơ liên quan và gửi tin “Tôi cần hướng dẫn xem biên lai gia hạn”. | Mở ngay hội thoại mới, trạng thái chờ tiếp nhận; không cần nhập tiêu đề hoặc gửi đơn riêng. |
| 2 | Staff | Mở Chờ tiếp nhận tại cơ sở và chọn Nhận trò chuyện. | Staff trở thành người phụ trách, trạng thái in_progress. |
| 3 | Staff | Gửi tin PUBLIC hướng dẫn khách mở hồ sơ gia hạn và chọn Xem biên lai. | Khách đọc được hướng dẫn trong cuộc trao đổi. |
| 4 | Customer | Làm theo và phản hồi đã xem được biên lai. | Tin nhắn được lưu, Staff nhận phản hồi. |
| 5 | Staff | Báo kết quả: “Đã hướng dẫn, khách xác nhận xem được biên lai.” | Ticket resolved, chờ khách xác nhận. |
| 6 | Customer | Kiểm tra và chọn Xác nhận kết quả và đóng. | Ticket closed; lưu kết quả, thời điểm và thông báo theo luồng. |

**Kết thúc:** Khách xác nhận vấn đề đã được giải quyết. Staff tự nhận yêu cầu; Manager không cần phân công hay duyệt đóng. Đóng support không tự thực hiện thanh toán hoặc gia hạn.

## Phiếu ghi nhận khi chạy

| Flow | Mã hồ sơ thực tế | Người chạy / thời điểm | Kết quả | Minh chứng |
| --- | --- | --- | --- | --- |
| Đặt kho và nhận kho | | | Chưa chạy | |
| Trả kho | | | Chưa chạy | |
| Gia hạn | | | Chưa chạy | |
| Hỗ trợ | | | Chưa chạy | |

Tải lại dữ liệu sau các bước chính để kiểm tra đã lưu và đối chiếu ở vai trò còn lại. Tài liệu là hướng dẫn chạy, chưa phải xác nhận đã kiểm thử thành công trên môi trường thực tế.
