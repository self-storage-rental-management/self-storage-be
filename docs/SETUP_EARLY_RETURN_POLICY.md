# Quyết định nghiệp vụ: khách trả kho trong thời gian setup

**Trạng thái:** Đã triển khai FE/BE ngày 26/10/2026 trong working tree; chưa áp migration, chưa chạy test hoặc nghiệm thu thực tế. Chính sách áp dụng cho báo giá mới có phiên bản `SETUP_EARLY_EXIT_ONE_MONTH_V1`. Đây là điều khoản của project; khách phải xem và xác nhận trước khi đặt, Staff đối chiếu điều khoản trong hợp đồng đã ký khi check-in.

## 1. Phạm vi và cách phân luồng

- Trước bàn giao/check-in: khách hủy hoặc không đến thì đi theo nhánh hủy/no-show của reservation. Khoản trả trước khi đặt kho (`RESERVATION_DEPOSIT`) xử lý theo điều khoản của nhánh này. Không tạo `ReturnCase` và không áp dụng phí chấm dứt sớm của setup.
- Sau bàn giao, nếu khách yêu cầu rời kho và **đã dọn hết hàng, trả quyền truy cập, được Staff nghiệm thu trước `setupEndsAt`**, đi qua **một** luồng `ReturnCase` cho việc trả kho sớm trong setup. Trường hợp chưa đưa hàng vào và trường hợp đang chuyển hàng dùng cùng công thức; tình trạng hàng chỉ ảnh hưởng chi phí hư hại/vệ sinh/thiếu vật dụng có chứng cứ.
- Nếu đến lúc bàn giao thực tế đã qua `setupEndsAt`, áp dụng quyết toán trả kho của kỳ thuê đã bắt đầu. Không giữ giá ưu đãi setup chỉ vì ngày tạo yêu cầu còn trong setup. Đối chiếu mốc thời gian với timeline bàn giao thực tế, không chỉ dựa vào `RentalStatus.setup` có thể chưa được cập nhật.
- Không thêm `RentalStatus` tài chính “đang chuyển hàng”. Có thể lưu ghi chú/ảnh kiểm kê về hàng đã chuyển vào nếu cần vận hành. Không áp dụng quy tắc này cho renewal, overdue recovery hoặc khách chưa check-in.

## 2. Chính sách tiền cần thể hiện trong điều khoản

Khách chủ động chấm dứt sau bàn giao nhưng trước khi hết setup chịu **phí chấm dứt sớm một lần bằng một tháng đơn giá thuê đã chốt khi đặt kho**. Đây là khoản bồi hoàn theo điều khoản đã chấp nhận, không phải kỳ thanh toán `MONTHLY` và không thu lặp lại theo tháng. Tiền thuê của kỳ chưa bắt đầu được đưa vào quyết toán; cọc bảo đảm chỉ hoàn sau kiểm kê. Chi phí hư hại, vệ sinh, thiếu vật dụng và công nợ đã xác minh phải có căn cứ riêng, không mặc định thu thêm chỉ vì khách từng đưa hàng vào.

Phân biệt ba khoản:

1. `reservationDepositAmount`: khoản trả trước lúc đặt kho, **là một phần tiền thuê**, không cộng lại lần nữa khi hoàn.
2. `remainingRentalAmount`: phần tiền thuê thu khi check-in.
3. `securityDepositAmount`: cọc bảo đảm thu khi check-in, chỉ quyết toán theo tình trạng kho và công nợ.

Backend tính từ **số đã thực thu, xác minh và chưa hoàn**:

```text
tiền thuê thực thu còn lại = khoản trả trước đã xác minh + phần tiền thuê check-in đã xác minh − tiền thuê đã hoàn trước đó
cọc bảo đảm thực thu còn lại = cọc bảo đảm đã xác minh − cọc đã hoàn trước đó
phí chấm dứt sớm = monthlyPrice trong snapshot báo giá/hợp đồng đã chấp nhận
tổng khấu trừ = phí chấm dứt sớm + phí hư hại/vệ sinh/thiếu vật dụng được duyệt + công nợ đã đối soát
số được hoàn = max(0, tiền thuê thực thu còn lại + cọc bảo đảm thực thu còn lại − tổng khấu trừ)
số còn phải thu = max(0, tổng khấu trừ − tiền thuê thực thu còn lại − cọc bảo đảm thực thu còn lại)
```

Ví dụ: đã thực thu 6 triệu tiền thuê và 2 triệu cọc bảo đảm; đơn giá tháng đã chốt là 2 triệu; phí hư hại có chứng cứ 300 nghìn; chưa có khoản hoàn/công nợ khác. Hoàn `6 + 2 − 2 − 0,3 = 5,7` triệu. Nếu không có hư hại, hoàn 6 triệu. Không trừ riêng `reservationDepositAmount` thêm lần nữa.

Khoản phạt **phải được công bố trước khi ký**. Với reservation lịch sử chưa có điều khoản/snapshot này, không lấy đơn giá hoặc chính sách hiện tại để tự áp phí; giữ chờ đối soát thủ công. Công thức là nguồn duy nhất ở backend, FE chỉ hiển thị bảng kê.

## 3. Các thay đổi đã triển khai

| Thành phần | Nội dung đã sửa |
| --- | --- |
| Quote, snapshot và hợp đồng | `ReservationQuoteService` ghi phiên bản và phí bằng `monthlyPrice`; `ReservationCreationService` bắt buộc `acceptedSetupEarlyExitPolicyVersion` khớp quote, đưa phiên bản chấp nhận vào fingerprint và sao chép phí/phiên bản sang `ReservationPricingSnapshot`. `ReservationQuoteSummary` hiển thị điều khoản; `CustomerApp` có checkbox theo quoteId. Staff xác nhận `confirmedSetupEarlyExitPolicyVersion` khi upload hợp đồng ban đầu. Hệ thống lưu hợp đồng do Staff upload, không tự sửa nội dung PDF đã ký. |
| Điều hướng khi hủy | `CustomerReservationService.cancel` sau bàn giao gọi `createCustomerReturnRequest`, trả response có `setupReturnRentalId`/`setupReturnCaseId`. `CustomerReservationCard` mở `ReturnApiPanel` để tạo/xem yêu cầu; thông báo phản ánh yêu cầu trả kho, không báo đã hủy reservation. |
| Quyết toán | `SetupEarlyExitPolicy` xác định điều kiện từ thời điểm kho được trả thực tế và snapshot. `ReturnSettlementService.calculateSettlement` dùng chung cho kiểm kê và xem xét tranh chấp. Tự chốt sau SLA dùng bảng kê đã đối soát, không tính lại giá; mỗi hồ sơ chạy transaction riêng. Khi Staff nghiệm thu phải có `unitVacated=true`; phí kiểm kê trong setup cần ghi chú và tệp bằng chứng thuộc đúng hồ sơ. |
| Đối soát tiền | `ReturnFinancialReconciliation` kiểm tra toàn bộ tiền thuê và cọc bảo đảm thực thu khớp snapshot, Payment PAID có chứng cứ hợp lệ, chưa hoàn, chưa quyết toán. Sau đó mới đưa `netRentalAmount` vào `rentalRefundCredit`; khoản đặt trước không cộng thêm. Nếu từng hoàn một phần hoặc thiếu chứng cứ thì chặn để đối soát thủ công, chưa tự xử lý hoàn một phần phức tạp. Snapshot tài chính lưu `setup_statement_json` và lịch sử sửa bảng kê. |
| Return DTO và UI | `ReturnCase`/`ReturnCaseResponse` bổ sung `setupExitRequested`, `setupEarlyExit`, `setupReturnedAt`, `setupEarlyExitPolicyVersion`, `rentalRefundCredit`, `earlyTerminationFee`. Customer/Staff/Manager/Business dùng bảng kê API; biên lai và lịch sử khách hiển thị tiền thuê và phí một tháng. Giao diện legacy được giữ cho demo khi không xác thực API; không dùng để ghi nhận quyết toán API. |
| Hoàn tất và chứng từ | Bỏ nuốt lỗi đối soát khi xác nhận/thu/hoàn. `recordSettlement` kiểm tra quyền, scope, số thực thu/hoàn, checkbox xác nhận và chứng từ đúng hồ sơ, người tải và tệp thực tế. Gửi lại cùng biên lai sau completed chỉ trả kết quả cũ. Gỡ nút đóng nhanh thiếu chứng từ; API khách tự khai thanh toán không hoàn tất hồ sơ. `VnpayService.refund` chặn hoàn giao dịch gốc của hồ sơ đã bàn giao để tránh hoàn trùng với ReturnCase. |
| File | `FileStorageService` kiểm tra quyền và cơ sở khi upload RETURN_CASE; khách sở hữu hồ sơ được xem/tải chứng từ trả kho. |
| Database | Thêm `V2026102602__setup_early_exit_settlement.sql`: cột chính sách quote/snapshot, cột quyết toán return, snapshot JSON tài chính và mã biên nhận dài tối đa 120 ký tự. Chỉ gắn nhãn các yêu cầu setup cũ còn mở để buộc đối soát; không ghi giá/chính sách mới vào hợp đồng cũ, không sửa quyết toán đã hoàn tất. **Chưa áp migration.** |

Giữ các đường dẫn API hiện tại. Contract cần đồng bộ:

- `POST /api/customer/reservations`: thêm `acceptedSetupEarlyExitPolicyVersion` đúng quote mới.
- `POST /api/staff/check-ins/{id}/contract-signing`: thêm `confirmedSetupEarlyExitPolicyVersion` khi snapshot có điều khoản mới.
- `POST /api/customer/rentals/{rentalId}/return-request`: tạo hoặc đọc lại hồ sơ đang mở, không tạo yêu cầu trùng khi gửi lại.
- `POST /api/staff/returns/{returnId}/inspection`: thêm `unitVacated=true` cho yêu cầu xuất phát từ setup.
- `POST /api/manager/returns/{returnId}/review-dispute`: dùng cùng công thức backend; phí kiểm kê setup đã có chứng cứ chỉ được giảm khi xem xét, không tự thêm phí mới.
- `POST /api/staff/returns/{returnId}/settlement-receipts`: bắt buộc `paymentMethod`, `transactionReference`, `receiptFileId`, `confirmed=true`, `amount` khớp bảng kê. Staff xác nhận thực thu/hoàn tại quầy, không thêm bước Manager duyệt biên lai.
- `POST /api/business/returns/{returnId}/complete-refund`: cùng chứng cứ và số tiền đã xác nhận; người có quyền hoàn tiền thực hiện chuyển khoản/hoàn tiền mặt bên ngoài hệ thống rồi ghi chứng từ. Project chưa tự chuyển tiền qua API ngân hàng.
- Endpoint complete-refund của Staff/Manager dùng cùng chốt quyền và chứng từ; Manager mặc định chỉ xử lý tranh chấp, không tự có quyền execute refund.
- `POST /api/customer/returns/{returnId}/pay-settlement`: từ chối tự khai thanh toán thành công; hướng dẫn thanh toán tại cơ sở và lưu biên lai.

## 4. Triển khai và kiểm chứng

1. Đưa FE/BE lên cùng phiên bản và áp migration mới bằng quy trình Flyway của môi trường triển khai; sao lưu DB theo quy trình của nhóm. Lượt sửa này chưa khởi động lại dịch vụ hay thay đổi DB.
2. Bật integration ledger và cấu hình lưu file hợp lệ. Dùng quote/reservation tạo mới để kiểm tra chính sách; hồ sơ cũ thiếu điều khoản dừng ở bước đối soát, không tự thu phí mới.
3. Đưa điều khoản một tháng phí vào mẫu hợp đồng giấy/PDF nhóm đang sử dụng. Staff phải đối chiếu và xác nhận điều khoản khi upload bản đã ký.
4. Đã kiểm tra kiểu FE và biên dịch BE, bỏ qua test. Chưa nghiệm thu trình duyệt/database/gateway hoặc chuyển hoàn thực tế.
5. Đã cập nhật các test policy/quyết toán/đặt kho, transport FE, bảng kê, biên lai và lịch sử khách; mã test BE đã qua `test-compile -DskipTests`, FE qua typecheck. Chưa thực thi test. Khi fetch develop ngày 26/10/2026, phát hiện số migration `V2026102601` đã được dùng cho setup/inventory; migration local chưa áp dụng được đổi sang `V2026102602`.

## 5. Kiểm tra nghiệm thu khi triển khai

- Khách hủy/no-show trước check-in không bị tính phí chấm dứt sớm setup.
- Trả kho trước `setupEndsAt`, dù chưa đưa hàng vào hay đang chuyển hàng, chỉ có một khoản phí một tháng; phần tiền thuê đã trả trước và tại check-in được tính đúng một lần.
- Cùng tình huống nhưng có hư hại thực tế: bổ sung đúng khoản được Staff ghi nhận, khách xem và có thể khiếu nại.
- Yêu cầu trong setup nhưng thực trả sau `setupEndsAt` không được hoàn như chưa bắt đầu kỳ thuê.
- Dữ liệu cũ thiếu snapshot, payment chưa xác minh, đã hoàn một phần hoặc chứng từ chuyển hoàn lỗi: dừng để đối soát, không tự hoàn/không hiển thị `completed`.
- Gia hạn, overdue recovery và trả kho sau setup giữ công thức riêng; không nhận thêm phí chấm dứt sớm của setup.

**Đã triển khai code; chưa áp migration và chưa chạy test theo yêu cầu. Các kịch bản trên là hướng dẫn nghiệm thu, chưa phải kết quả đã chạy.**
