# Bảo toàn dữ liệu reservation khi thiếu snapshot

Ngày cập nhật: 26/10/2026. Không chạy migration hoặc sửa dữ liệu lịch sử trong lần thay đổi này.

## Backend

- ReservationSnapshotView chỉ đọc tên/kích thước/tải trọng/khung kệ từ unit_type_snapshot_json đã lưu trong quote. Bỏ fallback sang UnitType hiện hành.
- Thiếu tên: hiển thị “Thiếu dữ liệu lịch sử, cần đối soát”. Thiếu thông số dùng cho sửa khai báo hàng: trả conflict có yêu cầu đối soát, không dùng cấu hình kho mới để tính lại.
- Phản hồi reservation bổ sung pricingSnapshotStatus, pricingPolicyVersion, pricingQuotedAt, pricingExpiresAt, unitTypeSnapshot và snapshotWarnings. DTO unitTypeSnapshot chỉ chứa thông tin loại kho khách được biết.
- Snapshot giá thiếu hoàn toàn: vẫn đọc được bản ghi reservation, nhưng các khoản tiền không biết là null, không tự thay bằng 0 hoặc giá hiện tại.
- Kiểm tra bản snapshot giá/setup khớp quote gốc: phiên bản policy, mã gói, số tháng, thời điểm báo giá, giá và các khoản phải thu. Số ngày setup phải khớp quote, pricing snapshot và reservation.
- Thiếu hoặc lệch dữ liệu cần thiết: không mở URL thanh toán mới, không thu phần còn lại, không bắt đầu setup hoặc tạo mới phiếu giữ kho từ dữ liệu không đủ chứng cứ. Check-in hiển thị số ngày setup chưa xác minh là null.
- Quote cũ thiếu snapshot hàng/loại kho không được tự dùng hàng khai báo mới và thông số danh mục hiện tại để tạo reservation. Yêu cầu lấy quote mới, không sửa reservation đã tồn tại.
- Điều kiện kiểm tra policy/giá hiện tại trước khi chấp nhận một quote mới vẫn giữ; đây là bước kiểm tra giao dịch mới, không phải tính lại đơn đã chấp nhận.

## Frontend

- Hồ sơ đơn đã đặt dùng tên/thông số kho trong snapshot của chính reservation. Không lấy tên, kích thước hoặc tải trọng danh mục hiện hành thay cho dữ liệu thiếu.
- Bỏ suy số tháng từ chênh lệch startDate/endDate, chia tổng tiền để suy đơn giá, tính ngược tỷ lệ giảm giá hoặc mặc định setup/giảm giá bằng 0.
- Chỉ dựng bảng báo giá đã lưu khi backend xác nhận pricing snapshot đầy đủ và các trường cần thiết có giá trị hợp lệ. Dùng phiên bản policy và thời điểm báo giá thực đã lưu.
- Thiếu dữ liệu: hiển thị cảnh báo cần đối soát, không mở thao tác thanh toán dựa trên số tự suy. Staff không được coi số cần thu null là 0 để bỏ qua thu tiền.
- Các ngày bàn giao/setup/kỳ thuê đã ghi nhận vẫn được hiển thị theo dữ liệu gốc; không tính bù ngày từ policy hiện tại. Thông tin liên hệ cơ sở hiện hành phục vụ liên hệ, không được dùng thay giá/quyền lợi lịch sử.

## Giới hạn

- Không tự tái tạo snapshot đã mất và không tự điều chỉnh giá, setup hoặc khoản tiền đã thu.
- Có migration setup cũ đã backfill số ngày theo số tháng thuê. Lần sửa này không sửa migration đã áp dụng hoặc phục hồi giá trị nguyên thủy trước backfill; dữ liệu đã bị backfill trước đó vẫn cần đối soát bằng chứng nếu nghi ngờ. Việc các bản snapshot đang lưu khớp nhau không chứng minh nguồn gốc trước mọi lần chuyển đổi dữ liệu.
- Chưa bổ sung công cụ nhập/xác minh lại snapshot lịch sử. Hồ sơ bị chặn cần nhân viên có trách nhiệm đối chiếu chứng từ và xử lý theo quy trình dữ liệu của hệ thống; không lấy catalog mới để tự điền.
- Kiểm tra bằng FE typecheck, đối chiếu enum và BE build bỏ qua test. Không chạy test hoặc thao tác DB/gateway thực tế theo yêu cầu. Chưa commit/push.
