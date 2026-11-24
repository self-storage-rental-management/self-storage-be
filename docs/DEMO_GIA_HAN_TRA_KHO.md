# Kịch bản demo StorageHub — Gia hạn và trả kho

## Chuẩn bị

- Mở phiên riêng cho Customer, Staff và Manager hoặc BO. Staff phải có quyền thao tác tại cơ sở. Dùng tài khoản có quyền thu tiền cho nhánh thu thêm và tài khoản có quyền thực hiện hoàn tiền cho nhánh hoàn cọc.
- Chọn các hồ sơ demo độc lập trên DB hiện có. Ghi mã hồ sơ, mã gian kho, ngày hết hạn, tiền bảo đảm đã xác minh và tài khoản sở hữu trước khi trình bày.
- Chuẩn bị hợp đồng đã ký, ảnh nghiệm thu và tệp biên lai demo. Dùng VNPay Sandbox cho khoản cọc gia hạn. Kiểm tra callback và khung giờ ký còn chỗ trước buổi demo.
- GH-01 và TK-01 đến TK-04 là tên quy ước. Các mã thực tế lấy từ hệ thống. Số tiền trên màn hình lấy từ báo giá và bảng kê thực tế.

## 1. GH-01 — Gia hạn thành công

**Điều kiện:** Hồ sơ thuê đang hiệu lực, nằm trong cửa sổ cho phép gia hạn, có gói gia hạn hợp lệ tại cơ sở và chưa có yêu cầu mở.

| Bước | Vai trò | Thao tác và điểm cần thấy |
| --- | --- | --- |
| 1 | Customer | Vào **Hồ sơ thuê**, mở đúng gian kho, chọn **Gia hạn**. Chọn gói và phương án thanh toán. Đối chiếu kỳ mới, tổng tiền, khoản cọc và khoản còn lại trên báo giá. |
| 2 | Customer | Đồng ý điều khoản, gửi yêu cầu, chọn lịch đến ký và thanh toán cọc bằng VNPay Sandbox. Khi quay lại, đối chiếu giao dịch đã ghi nhận và ngày hết hạn cũ. |
| 3 | Staff | Mở hồ sơ gia hạn tại cơ sở, ghi nhận khách đến. Nếu còn tiền phải thu, dùng bảng kê hiện hành, nhập thông tin giao dịch và đính kèm biên lai theo yêu cầu. |
| 4 | Staff | Đối chiếu kỳ có hiệu lực trên hợp đồng, tải bản đã ký và hoàn tất gia hạn. |
| 5 | Customer / Manager | Mở lại hồ sơ. Đối chiếu ngày hết hạn mới, trạng thái hoàn tất, hợp đồng và biên lai. Tải lại trang để kiểm tra kỳ thuê chỉ cập nhật một lần. |

**Nhánh kiểm tra:** Mở lại thao tác gia hạn khi GH-01 còn yêu cầu chưa hoàn tất. Giao diện phải chỉ tới yêu cầu đang mở. Dùng hồ sơ khác để demo thao tác hủy khi hệ thống còn cho phép.

## 2. TK-01 — Trả kho và hoàn cọc

**Điều kiện:** Hồ sơ đủ điều kiện trả kho, có tiền bảo đảm đã xác minh và tổng phí thấp hơn số dư dùng để quyết toán.

1. Customer vào **Hồ sơ thuê → Trả kho**, chọn ngày, nhập lý do và gửi. Đối chiếu ngày hẹn và mã hồ sơ.
2. Staff mở **Trả kho** tại đúng cơ sở. Kiểm tra gian kho, chìa khóa/thẻ/ổ khóa được cấp, hàng còn lại và tình trạng kho. Nhập phí hợp lệ, đính kèm ảnh nếu cần, lưu kết quả nghiệm thu.
3. Customer xem bảng kê rồi chọn **Đồng ý quyết toán**. Hồ sơ chuyển sang chờ hoàn cọc và hiển thị số thực hoàn.
4. Người có quyền thực hiện hoàn tiền đối chiếu số tiền với bảng kê. Nhập phương thức, mã giao dịch, tải biên lai của hồ sơ, tích xác nhận và ghi nhận hoàn tiền.
5. Customer mở lại hồ sơ để kiểm tra biên lai và trạng thái hoàn tất. Staff kiểm tra kỳ thuê đã kết thúc và gian kho ở trạng thái sẵn sàng hoặc bảo trì theo nghiệm thu.

## 3. TK-02 — Trả kho có khoản thu thêm

1. Chọn hồ sơ có chi phí hoặc công nợ hợp lệ vượt số dư dùng để quyết toán. Lặp lại bước gửi yêu cầu, nghiệm thu và xác nhận bảng kê.
2. Kiểm tra trạng thái chờ thu thêm và số tiền phải thu. Staff mở **Thu tiền quyết toán trả kho**, nhập phương thức, mã biên nhận, tải biên lai và tích xác nhận.
3. Trước khi tải biên lai hoặc tích xác nhận, kiểm tra nút gửi chưa khả dụng. Sau khi gửi đủ, kiểm tra hồ sơ hoàn tất và bảng kê ghi đúng khoản đã thu.

## 4. TK-03 — Bù trừ hết và đóng hồ sơ

1. Chọn hồ sơ mà bảng kê trả về tiền hoàn và tiền cần thu đều bằng 0. Customer gửi yêu cầu, Staff nghiệm thu, Customer đồng ý.
2. Kiểm tra hệ thống hoàn tất hồ sơ ngay sau xác nhận, ngày kết thúc thuê và trạng thái kho khớp kết quả nghiệm thu.

## 5. TK-04 — Khách yêu cầu xem xét lại

1. Sau nghiệm thu, Customer nhập lý do cụ thể và chọn **Yêu cầu xem xét lại**. Hồ sơ chuyển sang khiếu nại, Staff nhìn thấy lý do.
2. Manager hoặc BO có quyền quản lý hồ sơ, điều chỉnh phí và duyệt hoàn kiểm tra chứng cứ, cập nhật khoản phí hợp lệ và ghi lý do xử lý.
3. Sau khi xử lý, đối chiếu bảng kê mới. Nếu còn tiền phải thu hoặc hoàn, tiếp tục theo TK-02 hoặc TK-01. Nếu số dư bằng 0, kiểm tra hồ sơ đã đóng.

**Ghi kết quả buổi demo:** Với mỗi hồ sơ, ghi mã thực tế, trạng thái trước/sau, số tiền và mã biên lai nếu có. Nếu báo giá hoặc bảng kê bị chặn do thiếu chính sách, dữ liệu giá cũ hoặc chứng từ, ghi thông báo lỗi và dùng hồ sơ demo đã đủ dữ liệu để trình bày luồng chính.
