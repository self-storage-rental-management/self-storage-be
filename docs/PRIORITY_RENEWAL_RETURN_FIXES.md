# Sửa các điểm ưu tiên về gia hạn và quyết toán

Ngày: 19/10/2026.

## Phạm vi đã thống nhất

Xử lý ba phần: hạn thanh toán gia hạn, bảo vệ khoản trả trước khi có sự cố từ cơ sở, quyết toán theo công nợ và cọc thực thu. Phần xử lý nợ tháng khi hợp đồng còn hạn được tạm gác theo yêu cầu của chủ dự án; không thêm mốc khóa ra vào/chấm dứt/thu hồi vì nợ tháng.

## 1. Tách hạn trả trước khỏi hạn thu khi ký

- `paymentDeadline` giữ nguyên ý nghĩa hạn khoản trả trước; giao dịch cổng thanh toán vẫn có thời hạn riêng.
- `signingBalanceDueAt` là giờ kết thúc lịch ký đã xác nhận, không lấy hạn trả trước.
- Khoản phải thu khi ký dùng hạn này trong sổ công nợ. Quá hạn khi còn tiền phải trả và thời gian máy chủ đã vượt hạn, theo kiểm tra quá hạn hiện có.
- Đổi lịch hợp lệ cập nhật hạn thu khi ký. Mỗi lần đổi được ghi vào `rental_ledger_due_changes`; không sửa chứng từ, hash lệnh thu hoặc ngày thanh toán ban đầu.
- Hạn hoàn tất ký và mốc chặn trước thu hồi vẫn áp dụng riêng. Quá giờ hẹn không tự đồng nghĩa với mất cọc hoặc thu hồi.
- API đọc mới cho customer/staff/manager: `GET /api/{role}/renewals/{id}/payment-deadlines`, trả `prepaymentDueAt`, `signingBalanceDueAt`, `signingDeadline`, `recoveryCutoff`.

## 2. Bảo vệ khoản trả trước khi chưa rõ trách nhiệm

- Trước khi tự ghi nhận mất cọc, kiểm tra các sự cố của hồ sơ và quyết định xác minh trách nhiệm, kể cả sự cố đã ghi nhận trước thời điểm trả trước nhưng chưa được xử lý rõ.
- Chưa có quyết định hợp lệ, dữ liệu mơ hồ hoặc có đề xuất ngoại lệ đang chờ xác nhận: `AWAITING_FAULT_REVIEW`.
- Có kết luận lỗi từ cơ sở: `FACILITY_FAULT`.
- Hai trạng thái này giữ tiền chờ xử lý, không tự ghi nhận mất cọc. Có thông báo cho bộ phận vận hành và sự kiện `DEPOSIT_REVIEW_REQUIRED`.
- Chỉ tiếp tục tác vụ mất cọc khi các sự cố đã được xác minh không phải lỗi từ cơ sở và các kiểm tra tiền/thời hạn khác đã đạt.
- Không tự đảo bút toán mất cọc cũ hoặc tự thực hiện hoàn tiền. Chuyển xử lý thu hồi vật lý và xử lý khoản trả trước vẫn là hai việc riêng.

## 3. Quyết toán theo tiền thực thu và công nợ thực tế

- Cọc bảo đảm được xác minh từ bảng giá đã chốt và các khoản thanh toán nhận kho có nguồn xác thực; tổng tiền phải khớp chứng từ. Không dùng giá thuê tháng để đoán tiền cọc đã thu.
- Chứng từ thiếu, tiền hoàn một phần chưa được phân loại rõ hoặc khiếu nại thanh toán chưa xử lý: dừng để đối soát, không coi là đã thu đủ.
- Khi kiểm kê hoặc quản lý xem xét lại, lấy các khoản đến hạn từ sổ công nợ; không dùng `outstandingFee` do FE tự nhập làm nguồn tiền nợ.
- Phí quá hạn đã nằm trong sổ công nợ không được cộng thêm lần nữa vào khoản nhập tại kiểm kê.
- Bản đối soát gắn phiên bản sổ công nợ, khoản thanh toán cọc gốc và danh sách phân bổ. Nếu công nợ thay đổi trước xác nhận/thu tiền, yêu cầu đối soát lại.
- Các lần đối soát trước được giữ trong `return_financial_snapshot_history`. Hoàn tất ghi phân bổ vào đúng nghĩa vụ, giảm số dư nợ và đánh dấu cọc đã được quyết toán để không sử dụng lần nữa.
- Chứng từ nhận kho đã xác thực nhưng chưa được phản ánh đầy đủ vào sổ công nợ được liên kết trong bản quyết toán; không thu lại các khoản ban đầu đã trả.
- Các khoản tương lai của thanh toán từng tháng và quy tắc chấm dứt sớm không được tự hủy hoặc tự miễn trong thay đổi này.

## 4. Chứng từ thu/hoàn tiền

- Bấm thanh toán từ phía khách không còn tự đánh dấu đã thu tiền.
- API nhân viên mới: `POST /api/staff/returns/{id}/settlement-receipts`.
- Body: `paymentMethod` (`CASH` hoặc `BANK_TRANSFER`), `transactionReference`, `receiptFileId`, `confirmed: true`, `amount` (số tiền đang đối chiếu). Nếu số tiền đã thay đổi, yêu cầu tải lại hồ sơ trước khi ghi nhận.
- Biên lai phải đang hoạt động, gắn `RETURN_CASE` và đúng hồ sơ, do nhân viên đang xử lý tải lên. Cần quyền xử lý trả kho, thu tiền và phạm vi cơ sở.
- Mã giao dịch và tệp biên lai không được dùng lại. Gửi lại đúng bộ chứng từ của quyết toán đã hoàn tất trả lại kết quả đã lưu; không ghi tiền lần hai.
- API hoàn cọc cũ nhận thêm `receiptFileId` và `paymentMethod`; không tự tạo mã chuyển tiền giả khi thiếu chứng từ.
- Hồ sơ thu hồi tiếp tục dùng API biên lai thu hồi hiện có, đồng thời cập nhật cùng sổ công nợ khi quyết toán.
- Trường hợp khấu trừ cọc vừa đủ, không có khoản thu thêm hoặc hoàn ra ngoài, không yêu cầu biên lai chuyển tiền không tồn tại; vẫn cần xác nhận quyết toán và bản đối soát hợp lệ.

## Database và giới hạn triển khai

- Migration `2026101903` thêm hạn thu khi ký, lịch sử đổi hạn và các bảng liên kết quyết toán. Hạn cũ của hồ sơ đã đóng được giữ để đối chiếu lịch sử; hồ sơ đang mở được bổ sung hạn đúng lịch ký bằng bản ghi điều chỉnh riêng.
- Hồ sơ trả kho cũ chưa có bản đối soát mới cần kiểm kê/xem xét lại trước khi hoàn tất tiền. Khách vẫn có thể yêu cầu xem xét lại, không bị khóa việc phản ánh do thiếu bản đối soát cũ.
- Chủ dự án đã duyệt dùng lại form biên lai hiện có cho trả kho thường. Form dùng chung phương thức, mã giao dịch, tải biên lai và xác nhận thực thu/hoàn; giữ nguyên CSS. Khách không còn tự đánh dấu đã thanh toán; công nợ được đối soát bởi BE.
- Không chạy kiểm thử tự động trong lượt triển khai này.
- Kiểm tra kiểu dữ liệu FE: thành công. Ngày 19/10/2026 lúc 21:14, đã sao lưu database local vào `.local/before-restart-20261019-211426.sql`, áp dụng thành công migration `2026101903` và khởi động lại BE/FE. FE cổng 8443 và BE `/v3/api-docs` cổng 8080 đều trả HTTP 200. Đây là kiểm tra khởi động, chưa xác nhận toàn bộ luồng nghiệp vụ.
