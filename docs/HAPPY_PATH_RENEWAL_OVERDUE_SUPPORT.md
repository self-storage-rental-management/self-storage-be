# Kịch bản happy path: gia hạn, quá hạn và hỗ trợ khách hàng

Bộ kịch bản rút gọn cho 4 flow chính (đặt kho, trả kho, gia hạn, hỗ trợ): [HAPPY_PATH_ALL_FLOWS.md](HAPPY_PATH_ALL_FLOWS.md). Tài liệu này giữ phần mô tả chi tiết trước đó cho gia hạn, quá hạn và hỗ trợ.

Đối chiếu code FE/BE ngày 23/10/2026. Dùng để chạy demo hoặc kiểm thử thủ công; tài liệu này chưa phải biên bản xác nhận đã chạy thành công trên dữ liệu thực tế.

## 1. Chuẩn bị chung

| Thành phần | Chuẩn bị |
| --- | --- |
| Môi trường | FE chạy cổng 8443; BE và DB chạy theo cấu hình project. |
| Customer | Tài khoản đang hoạt động, sở hữu hồ sơ thuê dùng trong kịch bản. |
| Staff | Được phân quyền thao tác tại đúng cơ sở của hồ sơ. |
| Manager | Được phân quyền quản lý đúng cơ sở; dùng để theo dõi, trao đổi nội bộ hoặc xử lý thu hồi khi đủ quyền. |
| Chính sách | Chính sách gia hạn và hỗ trợ đã công bố, đang có hiệu lực. |
| Thanh toán | VNPay Sandbox đã cấu hình và callback hoạt động; chuẩn bị chứng từ hợp lệ cho phần thu/hoàn tiền tại cơ sở. |
| Tệp | Chuẩn bị minh chứng kiểm kê, biên nhận và hợp đồng/biên bản phù hợp với thao tác. Tải tệp qua đúng biểu mẫu để gắn với hồ sơ. |
| Phiên đăng nhập | Nên dùng các cửa sổ hoặc hồ sơ trình duyệt riêng cho Customer, Staff, Manager. |

Chuẩn bị ba hồ sơ độc lập để các kịch bản không ảnh hưởng nhau:

- **GH-01:** Hồ sơ thuê còn hiệu lực và đã nằm trong khoảng được gửi yêu cầu gia hạn; gian kho đang được sử dụng, không có yêu cầu gia hạn đang mở, trả kho, tranh chấp hoặc công nợ khác chặn luồng.
- **QH-01:** Hồ sơ thuê đã quá hạn và chưa trả kho; dùng cho kịch bản thu hồi. Không có giao dịch thanh toán còn chờ xác minh hoặc đối soát chặn bàn giao thu hồi.
- **HT-01:** Yêu cầu hỗ trợ sẽ được tạo mới trong kịch bản; có thể liên kết với GH-01 hoặc chỉ chọn cơ sở.

Các mã GH-01/QH-01/HT-01 là tên quy ước trong tài liệu, không phải mã được seed sẵn. Ghi lại mã hồ sơ thực tế trước khi demo. Không sửa giờ hệ điều hành hoặc ngày thuê trong DB đang dùng chung để ép chuyển trạng thái.

## 2. Happy path gia hạn: khách trả cọc, đến ký và hoàn tất

**Mục tiêu:** Khách gia hạn thành công, thanh toán đủ và thời hạn thuê được cập nhật đúng một lần.

**Vai trò chính:** Customer và Staff. Manager có thể theo dõi; luồng mới không cần manager duyệt yêu cầu gia hạn.

**Dữ liệu:** GH-01; một gói thuộc đúng cơ sở, đang hoạt động và được chọn trong chính sách gia hạn; lịch ký có chỗ và nằm trong hạn hoàn tất ký.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Mở hồ sơ thuê GH-01 và chọn gia hạn. | Hệ thống cung cấp các gói và điều kiện gia hạn hiện hành của cơ sở. |
| 2 | Customer | Chọn gói, phương án thanh toán và lấy báo giá. | Hiển thị kỳ gia hạn, đơn giá, giảm giá, tổng tiền, cọc và phần còn lại. Chưa đổi ngày kết thúc thuê. |
| 3 | Customer | Kiểm tra điều khoản, xác nhận báo giá và gửi yêu cầu. | Tạo hồ sơ gia hạn với điều khoản đã chấp nhận; hệ thống xác nhận điều kiện, không chờ manager duyệt. |
| 4 | Customer | Chọn và lưu giờ hẹn đến ký còn khả dụng. | Lịch hẹn được lưu, hiển thị mốc giờ khách đến cơ sở. |
| 5 | Customer | Chọn thanh toán cọc, hoàn tất giao dịch trên VNPay Sandbox và quay lại hệ thống. | Giao dịch được xác thực, ghi nhận và phân bổ. Khối thanh toán hiển thị đã thanh toán, chờ hoàn tất ký; có thể xem biên lai khi đủ điều kiện. |
| 6 | Staff | Mở hồ sơ ký gia hạn tại cơ sở, đối chiếu khách và ghi nhận khách đến. Đính kèm minh chứng theo biểu mẫu nếu cần. | Ghi nhận lượt đến của đúng lịch hẹn; hồ sơ tiếp tục bước thanh toán còn lại. |
| 7 | Staff | Xem bảng kê hiện hành, chọn tiền mặt hoặc chuyển khoản; thu đúng số tiền, tải minh chứng và xác nhận đã thu. Với chuyển khoản, nhập mã giao dịch. | Ghi nhận khoản tiền còn lại và biên nhận, cập nhật số tiền cần thu. |
| 8 | Staff | Hoàn tất các xác nhận trên biểu mẫu, tải hợp đồng đã ký và chọn hoàn tất gia hạn. | Gia hạn hoàn tất; kỳ thuê được nối tiếp theo điều khoản đã chấp nhận. |
| 9 | Customer / Manager | Tải lại hồ sơ thuê và xem lịch sử thanh toán, hợp đồng. | Ngày kết thúc thuê mới và chứng từ khớp hồ sơ vừa hoàn tất; gian kho tiếp tục được sử dụng. |

**Ví dụ đối chiếu tiền:** Nếu đơn giá thực tế là 2.000.000 đ/tháng, gói 3 tháng giảm 5%, phương án `FULL_TERM`: tạm tính 6.000.000 đ, giảm 300.000 đ, tổng 5.700.000 đ, cọc 20% là 1.140.000 đ, còn lại khi ký 4.560.000 đ. Chỉ dùng các số này nếu dữ liệu đã cấu hình đúng như ví dụ; khi chạy phải lấy số tiền từ báo giá và bảng kê của hệ thống.

**Điểm chốt khi demo:**

- Thanh toán cọc thành công chưa làm thay đổi thời hạn thuê; chỉ hoàn tất gia hạn mới cập nhật kỳ thuê.
- Giờ hẹn đến ký và hạn cuối hoàn tất ký là hai mốc riêng. Qua khung phân bổ lịch 30 phút vẫn có thể ghi nhận khách đến trong hạn ký còn hiệu lực.
- Tải lại trang không tạo thêm thanh toán hoặc gia hạn thêm một lần nữa.
- Tiền cọc gia hạn là khoản trả trước cho kỳ gia hạn, được theo dõi riêng với tiền bảo đảm của hồ sơ thuê.

## 3. Happy path quá hạn

### 3.1. Các mốc dùng để giải thích khi demo

Ngày 1 là ngày liền sau ngày cuối được phép sử dụng kho. Tính theo ngày lịch Việt Nam, gồm cuối tuần.

| Mốc | Hành vi |
| --- | --- |
| Ngày 1–7 | Theo dõi quá hạn, nhắc khách hoàn tất gia hạn hoặc trả kho. Nội bộ vẫn phân mức cảnh báo ngày 1–3, 4–6 và ngày 7. |
| 17:00 ngày 7 | Chốt thời gian hoàn tất gia hạn trong giai đoạn quá hạn. |
| Từ 08:00 ngày 8 | Đủ điều kiện chuyển hồ sơ sang xử lý thu hồi, sau khi kiểm tra dữ liệu và các điều kiện liên quan. |

Ví dụ: ngày cuối sử dụng kho là **14/10/2026**, ngày quá hạn đầu là **15/10**, chốt gia hạn **17:00 ngày 21/10**, bắt đầu đủ điều kiện chuyển thu hồi **08:00 ngày 22/10**. Đây là ví dụ tính mốc, không yêu cầu đổi dữ liệu đang chạy sang các ngày này.

### 3.2. Kịch bản chính: xử lý thu hồi thành công

**Mục tiêu:** Hồ sơ đủ điều kiện được tiếp nhận, kiểm kê, quyết toán và hoàn tất thu hồi; kho chỉ được giải phóng sau bước hoàn tất.

**Vai trò:** Customer và người xử lý tại cơ sở. Chọn **một Staff hoặc Manager** có đủ quyền để thực hiện xuyên suốt; không có bước manager duyệt riêng giữa các thao tác thu hồi hiện tại.

**Điều kiện riêng:**

- QH-01 đã đến mốc bắt đầu thu hồi theo kỳ thuê hiện tại, chưa gia hạn hoàn tất và chưa trả kho.
- Để chạy bàn giao tự động, BE bật profile `shared-payment-schema` và hai cờ `storagehub.integration.recovery-tasks.enabled`, `storagehub.integration.renewal-auto-recovery.enabled`. Worker chạy định kỳ, không bảo đảm tạo nhiệm vụ đúng giây 08:00.
- Người xử lý có `rentals:read`, `returns:read`, `returns:process` và quyền thao tác tại cơ sở; bước thu/hoàn tiền cần thêm `payments:collect`.
- Chuẩn bị hồ sơ có số tiền thu thêm hoặc hoàn lại sau quyết toán để minh họa đầy đủ bước biên nhận. Số tiền phải do hệ thống tính.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Hệ thống | Worker kiểm tra QH-01 sau mốc bắt đầu thu hồi và bàn giao hồ sơ. | Tạo một nhiệm vụ thu hồi, ghi lịch sử/thông báo. Các yêu cầu gia hạn chưa hoàn tất được xử lý hủy theo quy trình. Kho chưa chuyển thành trống. |
| 2 | Manager | Mở **Theo dõi quá hạn**, tìm QH-01 và chọn chi tiết. | Hiển thị số ngày quá hạn, trạng thái và nhiệm vụ thu hồi đã được tạo. |
| 3 | Staff hoặc Manager | Chọn **Xem và xử lý thu hồi**. | Mở nhiệm vụ đã tồn tại. Việc mở xem chưa làm đổi trạng thái hoặc giải phóng kho. |
| 4 | Người xử lý | Chọn **Bắt đầu xử lý**. | Nhiệm vụ chuyển từ `OPEN` sang `IN_PROGRESS`. |
| 5 | Người xử lý | Kiểm kê thực tế; với happy path chọn tài sản khớp, không hư hỏng, ghi nhận chìa khóa/thẻ/khóa đã trả theo thực tế; phí phát sinh bằng 0 nếu không phát sinh. Chọn trạng thái sau thu hồi là sẵn sàng cho thuê, tải minh chứng và lưu kiểm kê. | Tạo hồ sơ trả kho liên kết và bảng quyết toán. Phí quá hạn, công nợ và tiền bảo đảm được đối chiếu từ nguồn hệ thống. |
| 6 | Customer | Mở hồ sơ trả kho liên kết, kiểm tra và xác nhận quyết toán. | Quyết toán chuyển sang chờ thu thêm hoặc chờ hoàn tiền theo số tiền thực tế. |
| 7 | Người có quyền thu/hoàn | Thực thu hoặc thực hoàn đúng bảng kê, chọn phương thức, tải biên nhận và xác nhận quyết toán. | Có chứng từ thu/hoàn; quyết toán hoàn tất. |
| 8 | Người xử lý | Sau khi tài sản đã được xử lý xong và gian kho được kiểm tra, tích các xác nhận, tải biên bản và chọn **Hoàn tất thu hồi và giải phóng kho**. | Nhiệm vụ đóng (`CLOSED`), hồ sơ thuê hoàn tất, gian kho chuyển sang trạng thái đã xác nhận sau kiểm kê. |
| 9 | Manager / Customer | Tải lại danh sách, hồ sơ và chứng từ liên quan. | Trạng thái nhất quán, lịch sử có đủ các bước, không tạo nhiệm vụ thu hồi trùng. |

Nếu số tiền cần thu và cần hoàn đều bằng 0, không tạo biên nhận thu/hoàn giả; chuyển tiếp theo trạng thái quyết toán hệ thống cung cấp. Trạng thái `maintenance` là nhánh kho cần bảo trì, khác với happy path kho sẵn sàng cho thuê ở trên.

### 3.3. Nhánh thành công khác: khách gia hạn kịp trong giai đoạn quá hạn

Dùng **hồ sơ riêng**, chưa đến hạn chốt 17:00 ngày 7 và còn đủ điều kiện gia hạn:

1. Manager xem mức quá hạn; Staff liên hệ khách.
2. Customer thực hiện quy trình gia hạn ở mục 2; Staff thu theo bảng kê, bao gồm các khoản quá hạn nếu hệ thống xác định phải thu.
3. Hoàn tất ký trong hạn được phép.
4. Kiểm tra kỳ thuê hiện tại đã được cập nhật, hồ sơ không còn bị đánh giá quá hạn theo kỳ cũ và không bị tạo nhiệm vụ thu hồi dựa trên ngày hết hạn cũ.

Không dùng QH-01 đã vào quy trình thu hồi để minh họa nhánh này.

## 4. Happy path support: tiếp nhận, xử lý và khách xác nhận đóng

**Mục tiêu:** Customer gửi yêu cầu, Staff tự nhận và giải đáp, Customer đồng ý kết quả và đóng yêu cầu.

**Tình huống mẫu:** Khách cần hướng dẫn xem biên lai gia hạn. Đây là yêu cầu hướng dẫn, phù hợp để demo mà không cần phát sinh giao dịch tài chính mới.

**Nội dung chuẩn bị:**

- Tiêu đề: `Hướng dẫn xem biên lai gia hạn`.
- Mô tả: `Tôi đã thanh toán cọc gia hạn. Nhờ cơ sở hướng dẫn cách xem biên lai và lịch đến ký.`
- Liên kết hồ sơ thuê GH-01 hoặc chọn đúng cơ sở nếu chạy support độc lập.
- Staff có `support:read`, `support:update`, quyền thao tác tại cơ sở; Manager có quyền xem và quyền quản lý hỗ trợ/phạm vi phù hợp nếu tham gia trao đổi nội bộ.
- Chính sách hiện hành cho phép Customer xác nhận đóng; xác nhận trong khoảng thời gian xem xét kết quả của chính sách.

| Bước | Vai trò | Thao tác | Kết quả mong đợi |
| --- | --- | --- | --- |
| 1 | Customer | Mở hỗ trợ khách hàng, tạo yêu cầu HT-01 với tiêu đề, mô tả và cơ sở/hồ sơ liên quan. | Tạo yêu cầu `open`; khách nhận được mã yêu cầu và có thể xem chi tiết. |
| 2 | Staff | Vào hàng chờ tại cơ sở, chọn HT-01 và nhận xử lý. | Staff trở thành người phụ trách, yêu cầu chuyển sang `in_progress`; khách và manager nhận thông báo theo luồng. |
| 3 | Staff | Gửi tin nhắn công khai: `Bạn mở hồ sơ gia hạn, tại phần Thanh toán chọn Xem biên lai. Giờ hẹn đến ký nằm trong phần Lịch ký.` | Customer đọc được câu trả lời trong cuộc trao đổi. |
| 4 | Customer | Phản hồi: `Tôi đã xem được biên lai và lịch ký, cảm ơn cơ sở.` | Tin nhắn được lưu; Staff nhận phản hồi của khách. |
| 5 | Staff | Chọn báo kết quả xử lý với nội dung: `Đã hướng dẫn khách xem biên lai và lịch ký; khách xác nhận đã truy cập được.` | Yêu cầu chuyển sang `resolved`, chờ khách xác nhận; nội dung kết quả hiển thị cho khách. |
| 6 | Customer | Kiểm tra kết quả và chọn **Xác nhận kết quả và đóng**. | Yêu cầu chuyển sang `closed`, lưu mốc xác nhận và thông báo cho các bên. |
| 7 | Manager | Xem yêu cầu trong danh sách hỗ trợ của cơ sở. | Thấy người phụ trách, trao đổi và kết quả cuối cùng; không phải người bấm đóng thay khách. |

**Bước tùy chọn để demo vai trò Manager:** Sau bước 2 và trước bước 5, Manager gửi tin INTERNAL cho Staff: `Kiểm tra đúng hồ sơ gia hạn và hướng dẫn khách mở biên lai đã ghi nhận.` Staff phản hồi nội bộ. Customer không nhìn thấy nội dung và tệp INTERNAL. Bước này không đổi trạng thái yêu cầu và không thay thế câu trả lời PUBLIC cho khách.

**Điểm chốt khi demo:**

- Staff tự nhận yêu cầu; happy path hiện tại không có bước manager phân công rồi duyệt kết quả.
- `resolved` là Staff báo đã xử lý; `closed` là yêu cầu đã đóng sau xác nhận của khách hoặc theo chính sách. Kịch bản này dùng khách chủ động xác nhận.
- Đóng support không tự xác nhận thanh toán, hoàn tiền, gia hạn hoặc thu hồi. Nếu hỗ trợ liên quan thao tác nghiệp vụ, thao tác đó phải được thực hiện ở đúng module.

## 5. Phiếu ghi nhận kết quả chạy

| Kịch bản | Mã hồ sơ thực tế | Người chạy / thời điểm | Kết quả | Minh chứng |
| --- | --- | --- | --- | --- |
| Gia hạn hoàn tất | | | Chưa chạy | |
| Quá hạn → thu hồi hoàn tất | | | Chưa chạy | |
| Quá hạn → gia hạn kịp hạn chốt (tùy chọn) | | | Chưa chạy | |
| Support → khách xác nhận đóng | | | Chưa chạy | |

Chụp các mốc chính: điều khoản gia hạn, thanh toán, hợp đồng hoàn tất; nhiệm vụ thu hồi, quyết toán, trạng thái kho sau xử lý; support từ mở yêu cầu đến khách xác nhận đóng.

## 6. Nguồn đối chiếu

- [Contract gia hạn](RENEWAL_API_CONTRACT.md), ưu tiên các cập nhật hiện hành ở đầu tài liệu.
- [Contract hỗ trợ](SUPPORT_API_CONTRACT.md), ưu tiên cập nhật Staff tự nhận và trao đổi INTERNAL ở đầu tài liệu.
- [Mốc quá hạn](../src/main/java/com/storagehub/service/overdue/RenewalOverdueTimeline.java).
- [Bàn giao thu hồi tự động](../src/main/java/com/storagehub/service/overdue/LocalRenewalAutoRecoveryService.java).
- [Xử lý nhiệm vụ thu hồi](../src/main/java/com/storagehub/service/overdue/RecoveryTaskOperationService.java).
