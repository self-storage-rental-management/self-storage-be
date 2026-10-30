# Ngày có hiệu lực của kỳ gia hạn

## Quy tắc đã chốt

- Chưa quá hạn khi hoàn tất ký: kỳ mới nối tiếp kỳ cũ. Ký sớm không khiến kỳ mới bắt đầu sớm.
- Đã quá hạn khi hoàn tất ký: kỳ mới bắt đầu vào ngày hoàn tất ký đủ điều kiện, theo giờ Việt Nam; không lấy ngày thanh toán.
- Ngày cuối kỳ mới = ngày bắt đầu cộng số tháng thuê rồi trừ một ngày. Backend vẫn lưu ngày kết thúc hợp đồng theo quy ước inclusive/exclusive đã xác minh của hồ sơ.
- Không cộng ngày setup miễn phí.

Ví dụ kỳ cũ dùng đến hết 31/10: ký ngày 26/10 thì kỳ mới bắt đầu 01/11. Hoàn tất ký ngày 05/11 thì kỳ mới bắt đầu 05/11; 01-04/11 được xử lý bằng nghĩa vụ quá hạn. Ngày 05/11 không đồng thời tính phí quá hạn trong bảng kê gia hạn.

## Cách áp dụng

- FE bỏ chọn ngày bắt đầu; báo giá dùng ngày do backend xác định. API từ chối ngày tùy chọn khác ngày hợp lệ tại thời điểm lấy báo giá.
- Ngày trong báo giá là dự kiến. Báo giá, số tiền đã được chấp nhận và quan hệ phân bổ tiền được giữ nguyên. Đối với gói trả cả kỳ, đổi ngày ký không tự tạo thêm giảm giá hoặc tính lại tiền thuê.
- Khi ký, Staff thấy kỳ có hiệu lực trong bảng kê backend và gửi lại ngày bắt đầu đã kiểm tra cùng chứng từ hợp đồng. Nếu ngày thay đổi trước khi hoàn tất, backend từ chối và yêu cầu tải lại/kiểm tra hợp đồng; không âm thầm dùng ngày cũ.
- Hoàn tất vẫn bắt buộc đủ tiền, có khách đến, còn hạn ký, chưa đến hạn thu hồi và còn quyền tiếp tục kho. Khoảng giữ kho thực tế được kiểm tra lại và tiêu thụ trong cùng giao dịch.
- Kỳ có hiệu lực được lưu trong chứng cứ hoàn tất V2, gắn với thời điểm/người hoàn tất và báo giá đã chấp nhận. Ngày kết thúc hợp đồng thuê được cập nhật theo kỳ này.
- API bổ sung `effectivePeriod` (dự kiến hoặc đã chốt). `acceptedTerms` và cột `renewals.new_end_date` giữ ngày đề nghị của báo giá để bảo toàn liên kết tài chính; ngày kết thúc hợp đồng thực tế nằm ở Rental và chứng cứ hoàn tất. Response `newEndDate` của bản ghi hoàn tất V2 trả ngày thực tế.
- Các bản ghi hoàn tất V1 cũ tiếp tục đọc theo chứng cứ cũ; không hồi tố đổi ngày.
- Nếu khoản phí đã ghi sổ trước thay đổi này bao gồm cả ngày ký, hệ thống yêu cầu đối soát khi số đã ghi vượt số phí hợp lệ mới. Không tự sửa số đã thu hoặc tự hoàn tiền.
- Điều khoản trả từng tháng cũ không được tự dịch kỳ khi có chênh lệch ngày; cần đối soát. Không mở thêm lựa chọn thanh toán tháng.

## Kiểm chứng

Chỉ FE typecheck và BE build bỏ qua test theo yêu cầu. Chưa kiểm chứng giao dịch trên DB/gateway thực tế. Cần khởi động lại BE để tiến trình hiện tại nhận mã mới. Các tích hợp giữ kho khác nếu chưa hỗ trợ tiêu thụ kỳ thực tế sẽ từ chối khi ngày thay đổi, thay vì bỏ qua kiểm tra an toàn.
