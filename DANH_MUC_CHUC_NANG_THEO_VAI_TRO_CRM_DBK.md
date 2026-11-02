# TÀI LIỆU ĐẶC TẢ DANH MỤC CHỨC NĂNG THEO VAI TRÒ (BA DOCUMENT)
**Hệ thống**: CRM DBK Trading — Module Quản lý tuyển sinh & Đào tạo lái xe  
**Nguồn khảo sát**: `https://cms.tuvanhoclaixe.vn/chinhsach.html`  
**Ngày lập**: 28/10/2026  
**File Excel tương ứng**: `DANH_MUC_CHUC_NANG_THEO_VAI_TRO_CRM_DBK.xlsx`

---

## 1. MA TRẬN PHÂN QUYỀN VAI TRÒ & MODULE (RACI MATRIX)

| STT | Mã Module | Tên Phân Hệ | Nhóm Nghiệp Vụ | Admin | Chính Sách | Sale (Tuyển sinh) | Đại Lý / CTV | Kế Toán | Đào Tạo | CSKH | Ban Giám Đốc |
| :---: | :---: | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `MOD-01` | **Tổng quan (Dashboard)** | Chỉ số & Bàn làm việc | Toàn quyền | Xem chỉ số CS | KPI cá nhân & Lead | Số liệu đại lý | Thu chi, công nợ | Tiến độ đào tạo | Phản hồi học viên | Dashboard điều hành |
| 2 | `MOD-02` | **Chính sách (Bảng giá)** | Bảng giá theo phiên bản | Cấu hình quyền | Tạo, sửa giá | Bán theo giá khách | Bán theo giá sàn | Đối chiếu bảng giá | Không phân quyền | Không phân quyền | Phê duyệt ban hành |
| 3 | `MOD-02` | **Chính sách (Chiết khấu)** | Hạn mức chiết khấu | Khóa/Mở hạn mức | Thiết lập hạn mức | Dùng hạn mức (max 5%) | Dùng hạn mức (max 3%) | Kiểm tra công nợ | Không phân quyền | Không phân quyền | Duyệt vượt hạn mức trần |
| 4 | `MOD-02` | **Chính sách (Khuyến mại)** | Chương trình & Voucher | Cấu hình kỹ thuật | Tạo CT, cấp suất | Áp mã thuộc quyền | Áp mã đối tác | Đối trừ chi phí KM | Không phân quyền | Cấp mã tri ân | Duyệt ngân sách KM |
| 5 | `MOD-02` | **Chính sách (Quà & Cam kết)** | Hiện vật & Cam kết dịch vụ | Kiểm vết nhật ký | Quản trị danh mục | Gắn cam kết theo khung | Không hứa ngoài CS | Theo dõi giá trị quà | Thực hiện cam kết | Đánh giá cam kết | Duyệt cam kết đặc thù |
| 6 | `MOD-03` | **Sale (Tuyển sinh)** | Khai thác & Chăm sóc Lead | Import, phân bổ | Xem hiệu quả Sale | Gọi, đổi trạng thái | Lead thuộc đại lý | Hồ sơ cần thu tiền | Không phân quyền | Phối hợp chăm sóc | Báo cáo doanh số |
| 7 | `MOD-04` | **Đại lý & CTV** | Kênh bán ngoài & Hoa hồng | Quản trị User DL | Cấp giá sàn, hạn mức | Hỗ trợ đại lý | Tạo Lead, gửi hồ sơ | Đối soát hoa hồng | Không phân quyền | Không phân quyền | Duyệt chính sách hợp tác |
| 8 | `MOD-05` | **Kế toán & Tài chính** | Học phí & Đợt đóng | Cấu hình cảnh báo | Đối chiếu doanh thu | Đôn đốc học viên đóng | Gửi xác nhận tiền | Thu tiền, lập phiếu | Kiểm tra học phí DAT | Không phân quyền | Báo cáo tài chính, dòng tiền |
| 9 | `MOD-06` | **Đào tạo & Vận hành** | Lý thuyết, DAT & Sát hạch | Cấu hình tham số | Theo dõi cam kết | Theo dõi lịch học | Xem lịch học viên DL | Chặn hồ sơ nợ tiền | Xếp lớp, xe, DAT, thi | Tiếp nhận phản ánh xe | Báo cáo tỷ lệ đỗ |
| 10 | `MOD-07` | **CSKH & Khiếu nại** | Chăm sóc & Khảo sát | Cấp quyền module | Xem phản hồi CS | Nhận phản hồi học viên | Nhận phản hồi từ DL | Không phân quyền | Phối hợp đổi GV/xe | Tiếp nhận khiếu nại | Báo cáo chỉ số CSAT |
| 11 | `MOD-08` | **Phê duyệt (Workflow)** | Luồng duyệt ngoại lệ | Xem log duyệt | Xem duyệt giá | Tạo phiếu đề xuất | Gửi yêu cầu trùng Lead | Duyệt hoàn tiền | Duyệt danh sách thi | Không phân quyền | Phê duyệt tối cao |
| 12 | `MOD-09` | **Ban QT (Báo cáo)** | Báo cáo quản trị cấp cao | Xem log hệ thống | Báo cáo ROI chính sách | Xem KPI cá nhân | Xem doanh số đại lý | Báo cáo P&L khóa học | Báo cáo năng suất xe | Báo cáo chất lượng | Toàn quyền tra cứu |
| 13 | `MOD-10` | **Admin (Quản trị)** | User, Data, KPI, Cấu hình | Toàn quyền | Không phân quyền | Không phân quyền | Không phân quyền | Không phân quyền | Không phân quyền | Không phân quyền | Giám sát Admin |
| 14 | `MOD-11` | **Biên bản & Hồ sơ** | Bàn giao hồ sơ & Thỏa thuận | Lưu trữ điện tử | Kiểm tra căn cứ CS | Lập biên bản bàn giao | Gửi biên bản nhận | Ký xác nhận chứng từ | Nhận hồ sơ vào khóa | Lưu biên bản xử lý | Tra cứu pháp lý |
| 15 | `MOD-12` | **Dự thảo chi phí** | Định mức & Ngân sách khóa | Cấu hình bảo mật | Xem định mức hoa hồng | Không phân quyền | Không phân quyền | Dự toán chi phí sân/thi | Kế hoạch xe, xăng dầu | Không phân quyền | Phê duyệt định mức khóa |

---

## 2. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 1: ADMIN (QUẢN TRỊ HỆ THỐNG)
*Mã vai trò: `ADM` | Phạm vi: Toàn quyền cấu hình người dùng, kho data, phân quyền module, chỉ tiêu KPI và giám sát kỹ thuật*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `ADM-F01` | Quản trị Tài khoản | Tạo tài khoản người dùng | Create | Tạo mới tài khoản cho mọi vai trò (Sale, CSKH, Kế toán, Đào tạo, Đại lý). Nhập họ tên, email, SĐT, chọn vai trò và đơn vị trực thuộc. | Form tạo User, vai trò, chi nhánh | Tài khoản kích hoạt, gửi mật khẩu khởi tạo | Không được tự cấp quyền vượt cấp |
| 2 | `ADM-F02` | Quản trị Tài khoản | Khóa / Mở khóa tài khoản | Update | Chủ động khóa tài khoản khi nhân viên nghỉ việc hoặc đại lý ngừng hợp tác. Dữ liệu lịch sử chăm sóc và hồ sơ phát sinh vẫn bảo toàn nguyên vẹn. | User ID, lý do khóa | Tài khoản chuyển trạng thái Đã khóa / Hoạt động | Lưu vết lý do vào Audit Log |
| 3 | `ADM-F03` | Phân quyền Module | Bật / Tắt module cho User | Update | Bật/tắt quyền truy cập từng module cụ thể trên từng tài khoản. Thực hiện tách biệt vai trò Quản trị Giá/Khuyến mại sang Chính sách thương mại. | Danh sách module, User ID | Menu hiển thị tương ứng trên giao diện | Đã tách Quản trị Giá sang vai trò CS |
| 4 | `ADM-F04` | Quản trị Data Lead | Import danh sách Lead | Create | Tải file Excel data khách hàng vào kho tổng. Tự động kiểm tra định dạng và loại bỏ dòng lỗi/trùng số điện thoại. | File Excel `DATA_T7.xlsx` | Lead hợp lệ nạp vào Kho tổng, báo cáo dòng trùng | Tự động check trùng SĐT ngay khi import |
| 5 | `ADM-F05` | Quản trị Data Lead | Phân bổ data cho Sale | Update | Duyệt phiếu xin cấp data từ Sale hoặc chia data từ Kho tổng dựa trên định mức tồn data hiện tại và chỉ tiêu tuyển sinh. | Phiếu xin data, số lượng Lead | Lead chuyển từ Kho tổng sang giỏ data Sale | Chỉ cấp khi Sale dưới định mức tồn |
| 6 | `ADM-F06` | Quản trị Data Lead | Thu hồi Lead tự động / Thủ công | Update | Cài đặt quy tắc thu hồi Lead về Kho tổng khi đủ 3 điều kiện: (1) Quá 45-60 ngày không chốt, (2) Trạng thái Lạnh, (3) Không có lịch hẹn mới. | Cấu hình tham số ngày thu hồi | Lead thu hồi về Kho tổng để tái phân bổ | Hệ thống tự quét định kỳ hàng ngày |
| 7 | `ADM-F07` | Quản trị Data Lead | Xử lý tranh chấp trùng Lead | Review | Tiếp nhận yêu cầu khiếu nại trùng Lead giữa Sale và Đại lý. Kiểm tra chấm điểm 4 tiêu chí: lịch sử tương tác, nguồn, trạng thái, kênh liên hệ lại. | Mã tranh chấp, lịch sử cuộc gọi | Bàn giao Lead cho bên có điểm ưu tiên cao hơn | Điểm chấm tự động theo quy tắc hệ thống |
| 8 | `ADM-F08` | Quản trị Chỉ tiêu KPI | Thiết lập & Điều chỉnh KPI | Create/Update | Nhập chỉ tiêu tuyển sinh (số học viên, doanh thu kỳ) cho từng Sale và Đại lý. Lưu vết các phiên bản điều chỉnh (v1 bản gốc, v2 điều chỉnh có lý do). | Kỳ tháng/quý, bảng KPI | Chỉ tiêu hiển thị trên dashboard của Sale | Mỗi lần sửa đều sinh phiên bản mới |
| 9 | `ADM-F09` | Quản trị Chỉ tiêu KPI | Khóa kỳ đánh giá KPI | Update | Khóa dữ liệu kỳ kinh doanh đã kết thúc để phục vụ tính thưởng, hoa hồng và khóa sổ kế toán, ngăn sửa đổi số liệu hồi tố. | Kỳ cần khóa (Tháng 06/2026) | Trạng thái chuyển sang Đã khóa | Chỉ Ban Giám đốc mới có quyền mở khóa |
| 10 | `ADM-F10` | Cấu hình Kỹ thuật | Cài đặt quy tắc phân loại Lead | Config | Cấu hình tham số trạng thái Lead: Nóng (hẹn trong vòng X ngày), Ấm (hẹn cách Y ngày), Lạnh (quá hạn hoặc gọi không nghe Z lần), Trống (chưa gọi). | Ngưỡng số ngày, số cuộc gọi | Hệ thống tự động cập nhật nhãn Nóng/Ấm/Lạnh | Áp dụng chung cho toàn bộ Sale và Đại lý |
| 11 | `ADM-F11` | Cấu hình Kỹ thuật | Thiết lập ngưỡng cảnh báo | Config | Cài đặt bộ kích hoạt cảnh báo: Lead luân chuyển nhiều (>3 lần), Tài khoản không đăng nhập (>60 ngày), Sắp chạy DAT chưa đủ tiền (gửi Kế toán/Sale). | Ngưỡng cảnh báo, vai trò nhận tin | Thông báo đẩy (alert) trên hệ thống | Có thể bật/tắt từng loại cảnh báo |
| 12 | `ADM-F12` | Nhật ký & Giám sát | Tra cứu Audit Trail toàn hệ thống | Read | Xem toàn bộ nhật ký thao tác: Thời gian, Người thực hiện, Hành động, Đối tượng, Giá trị trước/sau, Lý do. Bảo toàn vĩnh viễn không thể xóa sửa. | Bộ lọc thời gian, user, hành động | Bảng tra cứu lịch sử thao tác chi tiết | Dữ liệu kiểm vết bảo mật tuyệt đối |

---

## 3. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 2: CHÍNH SÁCH THƯƠNG MẠI
*Mã vai trò: `CS` | Phạm vi: Quản trị toàn diện bảng giá, hạn mức chiết khấu, chương trình khuyến mãi, quà tặng hiện vật và cam kết thương mại*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `CS-F01` | Danh mục Chính sách | Quản lý vòng đời chính sách | CRUD | Khai báo chính sách mới, quản lý thời gian hiệu lực, đối tượng áp dụng (Khách hàng, Sale, Đại lý, CTV), người ban hành và trạng thái. | Mã CS, tên CS, loại, ngày bắt đầu/kết thúc | Chính sách được kích hoạt trên hệ thống | Phải gắn liền với quyết định ban hành |
| 2 | `CS-F02` | Danh mục Chính sách | Quản lý phiên bản (Versioning) | Create/Update | Tạo phiên bản điều chỉnh cho chính sách đang chạy. Lưu giữ lịch sử các phiên bản cũ và kiểm soát ngày chuyển tiếp để không làm gián đoạn hồ sơ cũ. | Phiên bản cũ, nội dung đổi, ngày áp dụng | Mã phiên bản mới (v1, v2, v3) | Hồ sơ cũ giữ nguyên điều khoản lúc ký |
| 3 | `CS-F03` | Quản trị Bảng giá | Khai báo bảng giá theo hạng bằng | Create/Update | Thiết lập 3 tầng giá cho từng hạng đào tạo (B1, B2, C...): (1) Giá niêm yết công khai, (2) Giá khuyến mại bán lẻ, (3) Giá sàn giao cho Đại lý/CTV. | Bảng giá theo hạng, kỳ áp dụng | Bảng giá chuẩn (BG-2026-03) | Sale không được bán dưới giá sàn |
| 4 | `CS-F04` | Quản trị Bảng giá | So sánh chênh lệch bảng giá | Read | Công cụ so sánh trực quan giữa bảng giá đang chạy và bảng giá dự thảo cho quý tiếp theo (BG-2026-03 vs BG-2026-04), đánh giá biến động chi phí sân bãi/xe. | Chọn 2 phiên bản cần so sánh | Bảng phân tích chênh lệch từng hạng | Phục vụ ban hành giá đón đầu quý mới |
| 5 | `CS-F05` | Chính sách Chiết khấu | Cài đặt hạn mức chiết khấu theo vai trò | Config | Quy định trần chiết khấu tối đa cho từng nhóm nhân sự: Sale (4% - 5%), Đại lý (3%), CTV (thỏa thuận). Quy đổi ra số tiền tối đa cho từng hạng bằng. | Vai trò, tỷ lệ %, hạng bằng | Bảng hạn mức chiết khấu có hiệu lực | Trần công ty tối đa 5% giá trị hồ sơ |
| 6 | `CS-F06` | Chính sách Khuyến mại | Thiết lập chương trình khuyến mại | CRUD | Tạo các chiến dịch khuyến mại (Tri ân học viên, Tặng giờ bổ túc). Cài đặt số lượng suất phát hành, số suất đã dùng, điều kiện nhận và thời hạn. | Mã KM, tên CT, số lượng suất, thời hạn | Voucher được sinh trong kho khuyến mại | Tự động khóa mã khi hết suất phát hành |
| 7 | `CS-F07` | Chính sách Khuyến mại | Ma trận chịu chi phí khuyến mại | Config | Phân bổ chi phí khuyến mại: Khoản nào do Trung tâm tài trợ từ quỹ marketing, khoản nào khấu trừ vào tiền hoa hồng của Sale/Đại lý. | Mã khuyến mại, vai trò chịu phí, mức giảm | Bảng ma trận trách nhiệm tài chính | Kế toán căn cứ bảng này để khấu trừ |
| 8 | `CS-F08` | Quà tặng & Ưu đãi | Quản lý quà tặng hiện vật | CRUD | Khai báo danh mục quà tặng đính kèm hồ sơ (mũ bảo hiểm, camera hành trình, bảo hiểm xe...). Khai báo đơn giá quy đổi thành tiền mặt để đối soát kế toán. | Tên quà, đơn giá quy đổi, điều kiện nhận | Danh mục quà sẵn sàng gắn vào hồ sơ | Giới hạn số lượng tồn kho quà tặng |
| 9 | `CS-F09` | Quà tặng & Ưu đãi | Quản lý gói ưu đãi dịch vụ | CRUD | Khai báo gói ưu đãi kèm khóa học: tặng thêm 2-4 giờ bổ túc, miễn phí thi thử cabin điện tử, ưu tiên chọn ngày học thứ 7/CN. | Tên ưu đãi, dịch vụ, giá trị quy đổi | Gói ưu đãi kích hoạt cho các kênh bán | Đào tạo căn cứ gói để xếp giáo viên |
| 10 | `CS-F10` | Cam kết Thương mại | Giám sát cam kết theo hồ sơ | Read/Review | Theo dõi các cam kết thương mại đặc thù do Sale/Đại lý cam kết với học viên. Kiểm tra tính hợp lệ so với khung chính sách, thời hạn và người duyệt. | Mã cam kết, mã hồ sơ, nội dung cam kết | Báo cáo cam kết đang chờ thực hiện | Cảnh báo cam kết sắp quá hạn |
| 11 | `CS-F11` | Phân quyền Chính sách | Thiết lập ma trận quyền áp dụng | Config | Quy định vai trò nào được dùng bảng giá nào, vai trò nào được áp dụng mã khuyến mãi nào, vai trò nào tuyệt đối không được tự ý cam kết ngoài chính sách. | Vai trò, danh mục quyền chính sách | Hệ thống ẩn/hiện nút tương ứng trên màn hình | Sale không thấy giá sàn của Đại lý |
| 12 | `CS-F12` | Thống kê & Đánh giá | Phân tích ROI và hiệu quả chính sách | Report | Báo cáo đo lường chi phí giảm giá trực tiếp, chi phí quà tặng so với doanh thu mang về. Thống kê tỷ trọng chiết khấu bình quân của từng Sale và chiến dịch. | Kỳ báo cáo, bộ lọc chiến dịch, Sale | Biểu đồ ROI & tỷ lệ % doanh thu | Căn cứ điều chỉnh chính sách kỳ sau |

---

## 4. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 3: SALE (KINH DOANH / TUYỂN SINH)
*Mã vai trò: `SALE` | Phạm vi: Khai thác Lead, tư vấn khóa học, áp dụng bảng giá/chiết khấu và theo dõi hồ sơ học viên*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `SAL-F01` | Quản lý Lead | Tiếp nhận Lead từ kho phân bổ | Read | Xem và tiếp nhận danh sách khách hàng tiềm năng được Admin phân bổ từ Kho tổng vào giỏ cá nhân. | Thông báo phân bổ Lead, ID Lead | Lead hiển thị trong danh sách chăm sóc | Thời hạn phản hồi đầu tiên trong 24h |
| 2 | `SAL-F02` | Quản lý Lead | Tạo Lead mới tự khai thác | Create | Nhập khách hàng do cá nhân tự tìm kiếm. Hệ thống kiểm tra số điện thoại ngay lập tức để tránh trùng với khách hàng đang được chăm sóc. | Họ tên, SĐT, hạng bằng, nguồn | Lead mới được tạo và gán cho cá nhân | Trùng SĐT sẽ báo lỗi hoặc tạo tranh chấp |
| 3 | `SAL-F03` | Chăm sóc Lead | Cập nhật tiến trình & Ghi chú cuộc gọi | Update | Ghi nhật ký cuộc gọi, nguyện vọng học viên, phân loại tình trạng (Nóng, Ấm, Lạnh) và đặt lịch hẹn gọi lại hoặc hẹn nộp hồ sơ. | Nội dung trao đổi, trạng thái, ngày hẹn | Lịch sử chăm sóc lưu vào timeline của Lead | Lead quá hạn hẹn bị hệ thống hạ bậc |
| 4 | `SAL-F04` | Tư vấn & Báo giá | Áp dụng Bảng giá tuyển sinh | Read/Apply | Tra cứu bảng giá chính thức dành cho khách hàng theo đúng phiên bản đang áp dụng. Báo giá chuẩn cho từng hạng đào tạo B1, B2, C. | Hạng bằng học viên lựa chọn | Giá niêm yết / Giá bán khuyến mãi gắn vào đơn | Không được bán dưới giá sàn quy định |
| 5 | `SAL-F05` | Tư vấn & Báo giá | Áp dụng Chiết khấu trong hạn mức | Apply | Chủ động quyết định mức giảm giá/chiết khấu cho khách trong phạm vi hạn mức cá nhân được cấp (tối đa 4%-5% tương ứng với số tiền quy định). | Mức % hoặc số tiền giảm yêu cầu | Hồ sơ ghi nhận giá sau chiết khấu | Nhập vượt hạn mức hệ thống sẽ chặn |
| 6 | `SAL-F06` | Tư vấn & Báo giá | Áp dụng Mã Khuyến mại hợp lệ | Apply | Chọn mã khuyến mãi từ danh mục đang chạy (nếu còn suất và đúng đối tượng). Hệ thống tự động trừ tiền học phí hoặc ghi nhận quà tặng. | Mã voucher / chương trình KM | Giá trị khuyến mại khấu trừ trên phiếu thu | Mỗi hồ sơ chỉ áp dụng 1 mã chính |
| 7 | `SAL-F07` | Luồng Ngoại lệ | Lập Đơn đề xuất vượt hạn mức | Create | Khi khách yêu cầu giảm giá vượt trần cá nhân hoặc hoàn cảnh đặc biệt, Sale lập đơn gửi lên Ban Giám đốc phê duyệt, nêu rõ lý do và tiềm năng chốt. | Mức chiết khấu mong muốn, lý do | Đơn đề xuất chuyển vào hàng đợi duyệt của BGĐ | Chỉ khi được duyệt mới được thu tiền |
| 8 | `SAL-F08` | Tạo Hồ sơ Học viên | Chuyển Lead thành Hồ sơ chính thức | Create | Khi học viên đồng ý đăng ký, chuyển trạng thái Lead sang Hồ sơ. Nhập CCCD, ngày sinh, địa chỉ, chọn khóa học và gói đợt đóng học phí. | Thông tin CCCD, gói học phí, ảnh hồ sơ | Mã hồ sơ học viên được cấp (B2-2026-07-09) | Chuyển sang Kế toán theo dõi thu tiền |
| 9 | `SAL-F09` | Theo dõi Tài chính | Đôn đốc đóng học phí theo giai đoạn | Read/Update | Theo dõi tiến độ đóng tiền: Đợt 1 (Đăng ký), Đợt 2 (trước khi chạy DAT), Đợt 3 (trước thi). Nhận cảnh báo học viên sắp chạy DAT chưa đủ tiền để nhắc nợ. | Danh sách học viên sắp đến hạn đóng | Ghi chú nhắc nợ hoặc xác nhận chuyển khoản | Không đóng đủ đợt 2 sẽ bị hoãn DAT |
| 10 | `SAL-F10` | Bàn giao Hồ sơ | Chuyển giao hồ sơ cho Đào tạo | Update | Sau khi học viên hoàn thành thủ tục tài chính ban đầu, Sale thực hiện thao tác chuyển hồ sơ sang bộ phận Đào tạo để xếp lớp và phân bổ giáo viên. | Mã hồ sơ đã hoàn tất đợt 1 | Hồ sơ xuất hiện trên màn hình Đào tạo | Phải có xác nhận thu tiền từ Kế toán |
| 11 | `SAL-F11` | Theo dõi Chỉ tiêu | Xem Dashboard hiệu quả & KPI cá nhân | Read | Theo dõi tiến độ đạt chỉ tiêu theo tháng: số học viên đã chốt, tổng doanh thu thực hiện, % hoàn thành KPI, xếp hạng thi đua trong phòng kinh doanh. | Kỳ báo cáo hiện tại | Biểu đồ tiến độ, đánh giá Tốt/Nguy cơ | Dữ liệu cập nhật realtime theo phiếu thu |

---

## 5. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 4: ĐẠI LÝ & CỘNG TÁC VIÊN
*Mã vai trò: `DL/CTV` | Phạm vi: Khai thác học viên qua kênh đại lý, nộp học phí theo giá sàn, hưởng chiết khấu hợp đồng và đối soát hoa hồng*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `DL-F01` | Quản lý Hồ sơ Đại lý | Đăng ký hồ sơ học viên mới | Create | Nhập danh sách học viên đăng ký qua văn phòng đại lý. Nhập thông tin cá nhân, hạng học và tải lên giấy tờ CCCD/giấy khám sức khỏe. | Form nhập học viên đại lý | Mã hồ sơ học viên gắn mã đại lý (DL-03) | Check trùng SĐT với kho trung tâm |
| 2 | `DL-F02` | Bán hàng & Báo giá | Áp dụng Bảng giá sàn Đại lý | Read/Apply | Được tra cứu và áp dụng mức Giá sàn do Trung tâm cấp riêng cho đại lý. Đại lý tự chủ động thu khách nhưng nộp về trung tâm theo giá sàn. | Hạng bằng lái B1/B2/C | Giá sàn đại lý nộp về trung tâm | Không được yêu cầu xuất HĐ dưới giá sàn |
| 3 | `DL-F03` | Bán hàng & Báo giá | Áp dụng Chiết khấu Đại lý | Apply | Hưởng mức chiết khấu theo hợp đồng đại lý đã ký (ví dụ Đại lý Phú Nhuận hưởng 3% tương ứng 585k-840k/hồ sơ tùy hạng). | Hợp đồng đại lý còn hiệu lực | Giá trị chiết khấu đại lý được đối trừ | Mức % cố định theo phụ lục hợp đồng |
| 4 | `DL-F04` | Khuyến mại & Voucher | Áp dụng Mã khuyến mại đối tác | Apply | Sử dụng các mã khuyến mãi đặc thù dành riêng cho kênh đối tác (ví dụ mã KM-2026-09 Đối tác với hạn mức 1.000.000đ/suất). | Mã khuyến mãi đối tác | Khoản giảm giá áp dụng trên hồ sơ | Chỉ áp dụng mã mở quyền cho Đại lý |
| 5 | `DL-F05` | Xử lý Tranh chấp | Gửi yêu cầu giải quyết Trùng Lead | Create | Khi khách hàng đến đại lý nhưng báo số điện thoại đã có ở trung tâm, Đại lý gửi đơn khiếu nại lên Admin kèm minh chứng khách chủ động đến. | Mã Lead trùng, ảnh chụp tin nhắn/phiếu hẹn | Đơn gửi vào hàng đợi xử lý của Admin | Admin chấm điểm 4 tiêu chí phân xử |
| 6 | `DL-F06` | Tài chính & Thanh toán | Gửi xác nhận thanh toán học phí | Create | Gửi ủy nhiệm chi / biên lai nộp tiền học phí của học viên về tài khoản trung tâm để Kế toán đối chiếu và kích hoạt hồ sơ. | Ảnh ủy nhiệm chi, mã hồ sơ | Kế toán nhận thông báo xác nhận tiền về | Chỉ hợp lệ khi kế toán đã bấm khớp tiền |
| 7 | `DL-F07` | Đối soát Công nợ | Xem bảng kê đối soát hoa hồng | Read | Xem bảng tổng hợp danh sách học viên đã nộp tiền trong tháng, tổng doanh số, tổng tiền chiết khấu được hưởng và số hoa hồng thực nhận. | Kỳ đối soát tháng | Bảng kê chi tiết từng hồ sơ và số tiền | Xác nhận số liệu để Kế toán chi tiền |
| 8 | `DL-F08` | Theo dõi Học viên | Tra cứu tiến độ học tập của học viên | Read | Xem trạng thái hồ sơ học viên đại lý giới thiệu: đã hoàn tất hồ sơ, đã xếp lớp, đang chạy DAT hay đã có lịch thi sát hạch. | Mã hồ sơ học viên | Màn hình chi tiết tiến trình đào tạo | Chỉ xem được học viên thuộc mã đại lý mình |

---

## 6. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 5: KẾ TOÁN (TÀI CHÍNH)
*Mã vai trò: `KT` | Phạm vi: Quản lý thu học phí theo đợt, kiểm soát đối chiếu bảng giá/chiết khấu, quản lý công nợ và chi trả hoa hồng đại lý*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `KT-F01` | Thu học phí | Xác nhận thu học phí theo giai đoạn | Create/Update | Kiểm tra tiền về tài khoản ngân hàng hoặc thu tiền mặt. Lập phiếu thu chính thức phân bổ vào 3 giai đoạn: Đăng ký, DAT, Trước thi. | Ủy nhiệm chi, mã hồ sơ, số tiền nộp | Phiếu thu điện tử hợp lệ, cập nhật trạng thái | Tự động đồng bộ sang hồ sơ học viên |
| 2 | `KT-F02` | Kiểm soát Bán hàng | Đối chiếu Bảng giá & Chiết khấu hồ sơ | Review | Kiểm tra mức giá và chiết khấu Sale/Đại lý áp dụng có đúng bảng giá đang chạy và đúng hạn mức không. Chặn phiếu thu nếu sai lệch. | Hồ sơ đăng ký, bảng giá BG-2026-03 | Xác nhận tính hợp lệ về mặt tài chính | Kế toán không được tự sửa bảng giá |
| 3 | `KT-F03` | Kiểm soát Khuyến mại | Hạch toán chi phí Khuyến mại & Quà tặng | Update | Căn cứ ma trận trách nhiệm mã KM: Hạch toán khoản giảm trừ vào chi phí marketing trung tâm hoặc khấu trừ vào hoa hồng Sale/Đại lý. | Mã KM gắn trên hồ sơ, ma trận KM | Bút toán phân bổ chi phí chuẩn xác | Đảm bảo minh bạch doanh thu thuần |
| 4 | `KT-F04` | Quản lý Công nợ | Theo dõi công nợ học viên theo khóa | Read/Update | Theo dõi danh sách học viên còn nợ học phí giai đoạn 2 và giai đoạn 3. Gửi thông báo công nợ định kỳ và cảnh báo các khoản nợ quá hạn. | Danh sách hồ sơ theo khóa đào tạo | Báo cáo công nợ học viên chi tiết | Phục vụ khóa kỳ quyết toán |
| 5 | `KT-F05` | Cảnh báo Vận hành | Cảnh báo học viên chạy DAT chưa đủ tiền | Read/Alert | Hệ thống tự động lọc học viên có lịch khai giảng DAT trong 10-12 ngày tới nhưng chưa nộp đủ học phí đợt 2. Kế toán phối hợp với Sale đôn đốc thu tiền. | Lịch chạy DAT của Đào tạo, dư học phí | Danh sách cảnh báo gửi Sale & Kế toán | Không nộp đủ sẽ không cấp tài khoản DAT |
| 6 | `KT-F06` | Chi hoa hồng | Tính toán & Chi trả chiết khấu Đại lý | Create | Lập bảng tính chiết khấu cho từng đại lý/CTV dựa trên số lượng học viên thực tế đã nộp đủ tiền. Trình duyệt phiếu chi và chuyển khoản hoa hồng. | Bảng đối soát đại lý đã chốt số liệu | Phiếu chi hoa hồng, chứng từ chuyển tiền | Khấu trừ thuế TNCN theo quy định |
| 7 | `KT-F07` | Xử lý Hoàn phí | Thực hiện thủ tục hoàn phí khi rút hồ sơ | Create/Update | Xử lý học viên xin rút hồ sơ hoặc chuyển khóa. Tính toán số tiền được hoàn trả sau khi khấu trừ chi phí hồ sơ, khám sức khỏe và các giờ học thực tế. | Đơn rút hồ sơ có phê duyệt của BGĐ | Phiếu chi hoàn tiền, cập nhật trạng thái Đã rút | Phải có đầy đủ chữ ký xác nhận của học viên |
| 8 | `KT-F08` | Báo cáo Tài chính | Lập báo cáo doanh thu, dòng tiền khóa | Report | Tổng hợp số liệu doanh thu thực thu, doanh thu dự kiến, chi phí hoa hồng, chi phí sân bãi, nhiên liệu để ra báo cáo lãi/lỗ từng khóa đào tạo. | Dữ liệu thu chi của toàn bộ hồ sơ trong khóa | Báo cáo tài chính khóa học gửi Ban Quản trị | Phục vụ đánh giá hiệu quả kinh doanh |

---

## 7. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 6: ĐÀO TẠO & VẬN HÀNH
*Mã vai trò: `DT` | Phạm vi: Tổ chức giảng dạy lý thuyết, thực hành, giám sát thiết bị DAT, thực hiện cam kết đào tạo và tổ chức thi sát hạch*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `DT-F01` | Tiếp nhận Hồ sơ | Tiếp nhận hồ sơ đủ điều kiện đào tạo | Read/Update | Tiếp nhận hồ sơ học viên đã được Kế toán xác nhận hoàn tất nghĩa vụ tài chính ban đầu. Kiểm tra hồ sơ pháp lý (ảnh thẻ, giấy khám sức khỏe). | Danh sách hồ sơ chuyển từ Sale/Kế toán | Hồ sơ chính thức đưa vào danh sách xếp khóa | Chặn hồ sơ chưa có xác nhận nộp tiền |
| 2 | `DT-F02` | Tổ chức Đào tạo | Xếp lớp học lý thuyết & Cabin mô phỏng | Create/Update | Lập lịch khai giảng, phân bổ giảng viên dạy lý thuyết luật GTĐB, bố trí ca tập trên thiết bị mô phỏng lái xe (Cabin điện tử) theo quy chuẩn Cục ĐBVN. | Danh sách học viên trong khóa, phòng học | Thời khóa biểu học lý thuyết và cabin | Gửi thông báo lịch học tự động cho học viên |
| 3 | `DT-F03` | Tổ chức Đào tạo | Phân bổ giáo viên thực hành & Xe tập | Create/Update | Gán giáo viên hướng dẫn và xe tập lái cho từng học viên. Quản lý tải xe tập và lịch giảng dạy của từng giáo viên. | Danh sách xe, giáo viên thực hành | Bảng phân công giáo viên và xe tập | Đảm bảo tỷ lệ học viên/xe đúng quy định |
| 4 | `DT-F04` | Vận hành DAT | Kích hoạt & Giám sát phiên học DAT | Monitor | Kiểm tra điều kiện học phí đợt 2. Kích hoạt tài khoản học viên trên thiết bị giám sát hành trình (DAT). Giám sát tiến độ tích lũy 810km/840km. | Nhận diện khuôn mặt, thiết bị DAT trên xe | Phiên học hợp lệ truyền lên hệ thống Cục | Cảnh báo phiên học lỗi khuôn mặt/ngắt quãng |
| 5 | `DT-F05` | Thực hiện Cam kết | Tra cứu & Thực hiện Cam kết đào tạo | Read/Execute | Tra cứu các cam kết đặc thù gắn trên hồ sơ học viên (đổi giáo viên nếu không hợp, ưu tiên học thứ 7/CN, tặng 2 giờ bổ túc) để tổ chức thực hiện. | Mã hồ sơ, danh sách cam kết kèm theo | Bố trí giáo viên/xe đáp ứng đúng cam kết | Cập nhật trạng thái Đã thực hiện sau khi xong |
| 6 | `DT-F06` | Thi & Sát hạch | Lập danh sách thi tốt nghiệp | Create/Review | Rà soát học viên đã hoàn thành đủ số km DAT, hoàn thành môn lý thuyết và cabin mô phỏng để lập danh sách dự thi tốt nghiệp cấp chứng chỉ nghề. | Báo cáo tổng hợp tiến độ học viên | Biên bản hội đồng thi tốt nghiệp | Chỉ học viên đạt chuẩn mới được vào danh sách |
| 7 | `DT-F07` | Thi & Sát hạch | Đăng ký danh sách thi Sát hạch Sở GTVT | Submit | Lập hồ sơ và danh sách thí sinh đủ điều kiện tốt nghiệp gửi lên Sở GTVT để chốt lịch thi sát hạch cấp Giấy phép lái xe. | Danh sách tốt nghiệp, báo cáo DAT nghiệm thu | Quyết định tổ chức kỳ sát hạch của Sở | Tuân thủ nghiêm ngặt lịch chốt danh sách Sở |
| 8 | `DT-F08` | Trả kết quả | Cập nhật kết quả thi & Bàn giao GPLX | Update | Cập nhật kết quả thi sát hạch (Đạt/Trượt từng phần: Luật, Mô phỏng, Sa hình, Đường trường). Nhận GPLX từ Sở và bàn giao cho học viên. | Bảng điểm sát hạch chính thức từ Hội đồng | Trạng thái chuyển thành Đã cấp bằng | Lưu trữ hồ sơ gốc vào kho lưu trữ |

---

## 8. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 7: CHĂM SÓC KHÁCH HÀNG (CSKH)
*Mã vai trò: `CSKH` | Phạm vi: Khảo sát học viên định kỳ, tiếp nhận và điều phối giải quyết khiếu nại, duy trì mức độ hài lòng CSAT*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết QuẢ Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `CSK-F01` | Khảo sát Đầu khóa | Gọi chào mừng & Khảo sát sau nhập học | Create | Liên hệ học viên mới trong vòng 3 ngày: Chào mừng gia nhập, xác nhận lại thông tin khóa học, nhắc quy chế và ghi nhận mức độ hài lòng ban đầu. | Danh sách học viên mới từ Kế toán/Sale | Phiếu khảo sát chào mừng trên hệ thống | Phát hiện ngay các thông tin tư vấn chưa chuẩn |
| 2 | `CSK-F02` | Tiếp nhận Khiếu nại | Ghi nhận phản ánh & khiếu nại học viên | Create/Update | Tiếp nhận phản ánh qua hotline/fanpage: giáo viên gắt gỏng, xe tập cũ hỏng, bị hoãn lịch học, chậm tiến độ DAT, khúc mắc học phí. | Mã học viên, nội dung phản ánh, mức độ | Ticket khiếu nại được tạo và gán mã xử lý | Phân loại mức độ: Khẩn cấp / Bình thường |
| 3 | `CSK-F03` | Điều phối Xử lý | Chuyển tiếp & Giám sát giải quyết | Update | Chuyển ticket sang bộ phận liên quan (Đào tạo đổi giáo viên/xe; Kế toán kiểm tra tiền; Sale đối chiếu tư vấn). Giám sát hạn giải quyết không quá 48h. | Ticket khiếu nại, bộ phận chịu trách nhiệm | Biên bản xử lý khiếu nại có chữ ký các bên | Đôn đốc phản hồi đúng hạn cam kết |
| 4 | `CSK-F04` | Khảo sát Hoàn khóa | Khảo sát chất lượng sau thi tốt nghiệp | Create | Khảo sát đánh giá toàn diện sau kỳ thi: đánh giá giáo viên lý thuyết, thực hành, sân tập, xe tập, thái độ phục vụ (thang điểm 1-5 sao). | Danh sách học viên đã thi tốt nghiệp | Báo cáo chỉ số CSAT và đánh giá giáo viên | Căn cứ bình xét thi đua khen thưởng |
| 5 | `CSK-F05` | Chăm sóc Hậu mãi | Tặng mã khuyến mại tri ân & Giới thiệu | Apply | Chúc mừng học viên nhận bằng. Kích hoạt mã khuyến mãi tri ân (mã KM-2026-07) để học viên tặng người thân, bạn bè đăng ký khóa học mới. | Hồ sơ đã nhận bằng lái | Mã voucher ưu đãi gửi qua SMS/Zalo học viên | Gia tăng nguồn khách hàng giới thiệu tự nhiên |
| 6 | `CSK-F06` | Báo cáo CSAT | Tổng hợp báo cáo chất lượng dịch vụ | Report | Lập báo cáo tỷ lệ khiếu nại theo nguyên nhân, thời gian xử lý trung bình, top giáo viên được khen/chê, chỉ số hài lòng chung của học viên. | Bộ dữ liệu khảo sát và khiếu nại trong kỳ | Báo cáo chất lượng CSKH trình Ban Giám đốc | Đề xuất cải tiến quy trình vận hành |

---

## 9. BẢNG CHI TIẾT CHỨC NĂNG VAI TRÒ 8: BAN GIÁM ĐỐC / BAN QUẢN TRỊ
*Mã vai trò: `BGD/BQT` | Phạm vi: Báo cáo điều hành tổng thể, phê duyệt tối cao các ngoại lệ thương mại, duyệt bảng giá, KPI và quản trị rủi ro*

| STT | Mã Chức Năng | Nhóm Chức Năng | Tên Chức Năng | Quyền | Mô Tả Chi Tiết Việc Thực Hiện Được | Dữ Liệu Đầu Vào | Kết Quả Đầu Ra | Ràng Buộc & Ghi Chú |
| :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :--- |
| 1 | `BGD-F01` | Bàn làm việc Lãnh đạo | Xem Dashboard điều hành tổng hợp | Read | Theo dõi chỉ số trọng yếu theo thời gian thực: Doanh thu tuyển sinh, tỷ lệ hoàn thành KPI, số hồ sơ đang đào tạo, tỷ lệ đỗ sát hạch, công nợ tồn. | Bộ lọc thời gian (Tuần, Tháng, Quý) | Dashboard đồ họa biểu đồ trực quan | Cập nhật tự động đa chiều dữ liệu |
| 2 | `BGD-F02` | Phê duyệt Ngoại lệ | Duyệt Đơn đề xuất vượt trần chiết khấu | Approve/Reject | Xem xét và phê duyệt đơn đề xuất xin giảm giá vượt hạn mức cá nhân do Sale/Trưởng phòng gửi lên. Đánh giá tiềm năng khách và biên lợi nhuận. | Mã đơn đề xuất, tỷ lệ vượt hạn mức, lý do | Quyết định Phê duyệt / Từ chối kèm nhận xét | Chỉ khi BGĐ duyệt, Kế toán mới được thu tiền |
| 3 | `BGD-F03` | Phê duyệt Ngoại lệ | Duyệt Cam kết thương mại ngoài chính sách | Approve/Reject | Phê duyệt các cam kết đặc thù ngoài quy định chuẩn (cam kết thi bao đỗ, học 1 kèm 1 không phụ phí, hỗ trợ đưa đón tận nhà). | Nội dung cam kết đặc thù, người đề xuất | Biên bản cam kết được phê duyệt chính thức | Kiểm soát chặt chẽ rủi ro pháp lý và chi phí |
| 4 | `BGD-F04` | Chiến lược Giá & KM | Phê duyệt ban hành Bảng giá & Chiến dịch | Approve | Ký duyệt ban hành các phiên bản bảng giá tuyển sinh mới (quý mới) và ngân sách cho các chiến dịch khuyến mại lớn do Chính sách xây dựng. | Dự thảo bảng giá mới, ngân sách KM | Chính sách được kích hoạt toàn hệ thống | Căn cứ pháp lý cao nhất trong vận hành giá |
| 5 | `BGD-F05` | Quản trị Chỉ tiêu | Phê duyệt điều chỉnh & Khóa sổ KPI | Approve | Phê duyệt chỉ tiêu tuyển sinh giao cho các phòng kinh doanh và đại lý. Phê duyệt điều chỉnh chỉ tiêu giữa kỳ. Phê duyệt khóa sổ kỳ kinh doanh. | Tờ trình giao/điều chỉnh KPI từ Admin | Quyết định giao KPI chính thức | Khóa dữ liệu tính thưởng và hoa hồng |
| 6 | `BGD-F06` | Quản trị Rủi ro | Giám sát hệ thống cảnh báo rủi ro | Monitor | Tiếp nhận cảnh báo rủi ro: KPI giảm liên tục 3 kỳ, đơn đề xuất điều chỉnh tăng đột biến, công nợ vượt trần, tỷ lệ trượt DAT cao bất thường. | Trung tâm cảnh báo rủi ro tự động | Chỉ đạo thanh tra hoặc điều chỉnh chính sách | Ngăn chặn thất thoát và khủng hoảng vận hành |
| 7 | `BGD-F07` | Báo cáo Quản trị | Tra cứu Báo cáo tài chính & Hiệu quả kinh doanh | Report | Xem toàn bộ báo cáo phân tích chuyên sâu: Báo cáo P&L theo từng khóa đào tạo, ROI chính sách thương mại, năng suất giáo viên và xe tập. | Kho báo cáo quản trị tổng hợp | Báo cáo phân tích chiến lược | Phục vụ hoạch định mở rộng chi nhánh/sân |

