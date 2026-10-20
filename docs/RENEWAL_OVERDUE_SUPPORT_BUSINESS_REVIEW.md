# Rà soát nghiệp vụ gia hạn, quá hạn và hỗ trợ khách hàng

> Cập nhật 20/10/2026: đã triển khai làm gọn nguồn kỳ thuê, trạng thái thu hồi và tách giao dịch đối soát cọc. Chi tiết phạm vi, chuyển đổi hồ sơ cũ và điều kiện còn giữ: [Luồng quá hạn đã đơn giản hóa](OVERDUE_SIMPLIFIED_FLOW.md).

- Ngày ghi nhận: 19/10/2026.
- Phạm vi: code BE, FE và tài liệu nghiệp vụ hiện có.
- Trạng thái: ghi nhận kết quả rà soát và đề xuất; chưa sửa code theo tài liệu này.
- Giới hạn: các nhận định dựa trên đọc code, chưa chạy kiểm thử trong lượt rà soát. Mức ưu tiên dưới đây là đề xuất, chưa phải chính sách đã được nhóm phê duyệt.

## 1. Gia hạn và thanh toán

### 1.1. Hạn thu phần còn lại đang sớm hơn lịch ký

**Ưu tiên: cao.**

- Nghiệp vụ đã chốt: khách trả trước 20%; khi ký mới thanh toán phần còn lại phải thu.
- Hiện tại: khoản trả trước và phần còn lại dùng chung hạn thanh toán khoản trả trước. Hạn này có thể là một giây trước lịch ký.
- Rủi ro: khách chưa đến giờ ký đã bị ghi nhận khoản còn lại quá hạn.
- Đề xuất: tách hạn thanh toán khoản trả trước khỏi hạn thanh toán khi ký; xác định rõ thời điểm ghi nhận quá hạn cho từng khoản.

Vị trí đối chiếu:

- `src/main/java/com/storagehub/service/ledger/FinancialContractSpecifications.java:81`
- `src/main/java/com/storagehub/service/ledger/RenewalDepositObligationWriter.java:58`
- `src/main/java/com/storagehub/service/renewal/RenewalWorkflowService.java:148`

### 1.2. Có nguy cơ mất cọc gia hạn dù nguyên nhân từ cơ sở

**Ưu tiên: cao.**

- Hiện tại: tác vụ ngày 8 kiểm tra thời hạn, tiền đã nhận và hồ sơ hoàn tiền; chưa kiểm tra nguyên nhân lỗi từ cơ sở ngay trước khi tự động ghi nhận mất cọc.
- Rủi ro: cơ sở gây lỗi nhưng nhân viên chưa kịp lập hồ sơ hoàn tiền thì tác vụ vẫn có thể ghi nhận mất cọc.
- Đề xuất: hồ sơ có sự cố từ cơ sở hoặc đang xác minh trách nhiệm phải được giữ chờ xử lý tiền; không tự ghi nhận mất cọc khi trách nhiệm chưa được xác định.
- Cần phân biệt: khoản trả trước gia hạn là một phần tiền thuê, không phải tiền cọc bảo đảm ban đầu của hồ sơ thuê.

Vị trí đối chiếu: `src/main/java/com/storagehub/service/ledger/RenewalDepositSettlementService.java:76`.

### 1.3. Thanh toán từng tháng chưa được tích hợp đầy đủ trên FE

**Ưu tiên: tích hợp tiếp sau khi chốt BE.**

- BE đã hỗ trợ thanh toán từng tháng và toàn kỳ.
- FE hiện chỉ gửi mã gói khi lấy báo giá, chưa gửi lựa chọn hình thức thanh toán.
- BE mặc định yêu cầu thiếu hình thức thanh toán thành thanh toán toàn kỳ.
- Đây là phần tích hợp còn thiếu; trước đó phạm vi triển khai mới là BE.
- Đề xuất: bổ sung lựa chọn hình thức thanh toán, lịch kỳ, số tiền phải trả khi ký và thanh toán các kỳ tiếp theo. Thay đổi giao diện cần được review theo yêu cầu của chủ dự án.

Vị trí đối chiếu:

- FE: `self-storage-fe/src/services/renewalApi.ts:143`.
- BE: `src/main/java/com/storagehub/service/renewal/RenewalWorkflowService.java:60`.

## 2. Quá hạn, trả kho và thu hồi

### 2.1. Chưa có quy tắc đầy đủ cho nợ tháng khi hợp đồng còn hạn

**Ưu tiên: cao; cần chốt chính sách trước khi code.**

- Hiện tại đã có công nợ tháng và thông báo nhắc hạn.
- Đường chuyển thu hồi đang chỉ cho phép quá hạn thời hạn thuê, chưa xử lý đầy đủ khách nợ tiền tháng trong khi hợp đồng vẫn còn hạn.
- Không tự áp dụng mốc ngày 8 của hết hạn hợp đồng cho nợ tháng.

Những nội dung cần chốt:

1. Nợ bao nhiêu ngày thì tăng mức cảnh báo?
2. Khi nào hạn chế quyền ra vào và áp dụng những ngoại lệ nào?
3. Khi nào chấm dứt hợp đồng và chuyển thu hồi?
4. Khi khách thanh toán bù, khôi phục quyền và trạng thái bằng cách nào?
5. Phí chậm thanh toán được tính thế nào, nếu có?

Vị trí đối chiếu: `src/main/java/com/storagehub/service/overdue/OverdueService.java:71`.

### 2.2. Quyết toán trả kho chưa đồng bộ chặt với công nợ tháng

**Ưu tiên: cao.**

- Hiện tại: quyết toán nhận `outstandingFee` do nhân viên nhập và cộng vào các khoản khấu trừ; chưa tự đối chiếu khoản này với từng nghĩa vụ trong sổ công nợ.
- Rủi ro: bỏ sót nợ, thu trùng hoặc quyết toán xong nhưng sổ công nợ vẫn còn khoản chưa xử lý.
- Đề xuất: lấy các khoản nợ từ sổ công nợ làm nguồn đối chiếu; khoản điều chỉnh thủ công phải có lý do và dấu vết kiểm tra. Thu tiền, khấu trừ cọc, hoàn tiền và hủy nghĩa vụ phải được phân bổ vào đúng khoản.

Quy tắc trả kho trước hạn còn cần chốt:

- Tháng chưa sử dụng được hủy hay vẫn phải trả?
- Có phí chấm dứt sớm không?
- Tiền đã trả trước được hoàn thế nào?
- Có thời gian báo trước không, và tính theo ngày hay kỳ thanh toán?

Vị trí đối chiếu: `src/main/java/com/storagehub/service/ReturnSettlementService.java:192`.

### 2.3. Chưa trả hết nợ thì không giải phóng được kho

**Ưu tiên: trung bình đến cao.**

- Hiện tại: phải quyết toán hoàn tất mới được hoàn tất thu hồi và giải phóng kho.
- Tình huống vận hành: tài sản đã được xử lý, gian kho đã trống và đạt kiểm tra nhưng khách vẫn còn nợ chưa thu được.
- Rủi ro: gian kho tiếp tục bị giữ, không thể cho thuê lại.
- Đề xuất: tách hoàn tất xử lý vật lý khỏi hoàn tất thu hồi công nợ. Chỉ giải phóng khi tài sản đã được xử lý hợp lệ và kho đạt điều kiện; khoản nợ chưa thu tiếp tục được theo dõi riêng.
- Không ghi nhận khoản chưa thu là đã thanh toán để mở khóa thao tác.

Vị trí đối chiếu:

- `src/main/java/com/storagehub/service/overdue/RecoveryTaskOperationService.java:110`.
- `src/main/java/com/storagehub/service/overdue/RecoverySettlementGuard.java`.

### 2.4. Tiền cọc bảo đảm có thể bị suy ra từ giá thuê

**Ưu tiên: cao.**

- Hiện tại: khi thiếu dữ liệu cọc trong báo giá gốc, code lấy giá thuê tháng làm tiền cọc bảo đảm để quyết toán.
- Rủi ro: hoàn hoặc khấu trừ trên một khoản tiền chưa chứng minh đã thu.
- Đề xuất: xác định số cọc thực thu còn giữ từ chứng từ, phân bổ, hoàn tiền và khấu trừ trước đó. Dữ liệu cũ thiếu phải được đối soát, không tự coi giá thuê tháng là cọc đã thu.

Vị trí đối chiếu: `src/main/java/com/storagehub/service/ReturnSettlementService.java:582`.

### 2.5. Phạm vi của mốc ngày 8 cần được diễn đạt rõ

- Có thể giữ mốc ngày 8 để tạo nhiệm vụ thu hồi theo chính sách nhóm đã chốt.
- Tạo nhiệm vụ thu hồi không đồng nghĩa với cho phép tự thanh lý tài sản hoặc đưa kho sang trạng thái có thể cho thuê ngay.
- Cần phân biệt: tiếp nhận nhiệm vụ, liên hệ khách, xử lý tài sản, kiểm kê, quyết toán, kiểm tra kho và giải phóng kho.
- Tham khảo quy trình của nhà vận hành khác chỉ để đối chiếu cách tổ chức nghiệp vụ; không lấy thời hạn hoặc quy định nước ngoài làm chuẩn áp dụng cho dự án.

## 3. Hỗ trợ khách hàng

### 3.1. Khách phản hồi sau “Đã xử lý” chưa làm hồ sơ hoạt động lại

**Ưu tiên: trung bình đến cao.**

- Hiện tại: khách vẫn có thể gửi tin, nhưng tin nhắn không tự mở lại hồ sơ đã báo xử lý.
- Rủi ro: khách nhắn “vẫn chưa khắc phục” nhưng hồ sơ vẫn chờ xác nhận và có thể tiếp tục đi đến tự đóng.
- Đề xuất: phản hồi mới trong thời gian chờ xác nhận phải ngăn việc tự đóng trên kết quả cũ; cung cấp lựa chọn xác nhận hài lòng hoặc yêu cầu xử lý tiếp. Cần chốt rõ phản hồi nào đưa hồ sơ trở lại đang xử lý.

Vị trí đối chiếu: `src/main/java/com/storagehub/service/support/SupportService.java:172`.

### 3.2. Giao diện bỏ yêu cầu bổ sung nhưng thời gian xử lý vẫn phụ thuộc trạng thái đó

**Ưu tiên: trung bình; cần thống nhất chính sách.**

- BE chỉ tạm dừng thời gian xử lý khi hồ sơ ở trạng thái “Chờ khách bổ sung”.
- Nhân viên yêu cầu khách gửi giấy tờ bằng tin nhắn thông thường thì trạng thái không đổi.
- Rủi ro: thời gian chờ khách có thể bị tính thành thời gian nhân viên xử lý.
- Đề xuất: thống nhất lại cách tính thời gian phù hợp với giao diện nhắn tin đã chọn. Không suy ra trạng thái chờ chỉ bằng cách đoán nội dung tin nhắn.

Vị trí đối chiếu:

- `src/main/java/com/storagehub/service/support/SupportService.java:211`.
- `src/main/java/com/storagehub/service/support/SupportService.java:374`.

### 3.3. Cuộc trao đổi dài và bản nháp còn bất tiện

**Ưu tiên: trung bình.**

- Đổi tab có thể làm mất bản nháp và danh sách tệp đang chọn.
- Sau thao tác, phần trao đổi trở về trang tin đầu; với hơn 20 tin, người dùng có thể không thấy tin mới gửi.
- Đề xuất: giữ bản nháp riêng cho từng đối tượng trao đổi; sau gửi thành công đưa người dùng đến tin mới hoặc trang chứa tin đó.

Vị trí đối chiếu:

- FE: `self-storage-fe/src/views/support-api/SupportTicketDetail.tsx:123`.
- FE: `self-storage-fe/src/views/support-api/SupportTicketDetail.tsx:179`.
- BE: `src/main/java/com/storagehub/service/support/SupportService.java:276`.

## 4. Thứ tự xử lý đề xuất

1. Sửa hạn thu tiền khi ký và bảo vệ trường hợp lỗi từ cơ sở trước khi ghi nhận mất cọc.
2. Đồng bộ quyết toán với sổ công nợ; chốt chính sách trả kho trước hạn và xử lý nợ tháng.
3. Tách giải phóng kho khỏi thu hồi nợ, giữ đầy đủ điều kiện kiểm kê và xử lý tài sản.
4. Hoàn thiện phản hồi sau xử lý, tự đóng và cách tính thời gian xử lý hỗ trợ.
5. Tích hợp lựa chọn thanh toán từng tháng lên FE và xử lý trải nghiệm nhắn tin.

## 5. Những quyết định có thể tiếp tục giữ

- Gia hạn không cần quản lý duyệt.
- Thanh toán từng tháng: khoản trả trước bằng 20% tháng đầu, trừ vào tiền phải thu tháng đầu; khi ký thu 80% tháng đầu.
- Thanh toán toàn kỳ: khoản trả trước bằng 20% tổng tiền gia hạn; khi ký thu phần còn lại.
- Không giảm giá cho báo giá gia hạn mới theo quyết định của nhóm.
- Khoản trả trước gia hạn khác tiền cọc bảo đảm của hồ sơ thuê ban đầu.
- Hoàn tất gia hạn cần đủ điều kiện ký, khoản phải thu khi ký và chứng từ; các tháng tương lai chưa đến hạn không chặn hoàn tất.

Các điểm cần sửa chủ yếu nằm ở xử lý ngoại lệ và sự đồng bộ giữa các luồng, không yêu cầu thay đổi toàn bộ thiết kế nghiệp vụ.

## 6. Nguồn tham khảo vận hành

- [Safestore – Chính sách hoàn tiền và hủy](https://www.safestore.co.uk/terms-and-conditions/refund-cancellation-policy/): tham khảo quy tắc báo trước và nghĩa vụ thanh toán khi kết thúc thuê.
- [Public Storage – Quy trình xử lý tài sản do nợ tiền](https://help.publicstorage.com/rent-a-unit/rental-faqs/zzzzz-Storage_Auctions_and_the_Lien_Process): tham khảo việc liên hệ và thông báo trước khi xử lý tài sản.

Các nguồn này là ví dụ từ nhà vận hành nước ngoài, không phải kết luận pháp lý hoặc quy tắc bắt buộc áp dụng tại Việt Nam.

> Các đường dẫn và số dòng phản ánh phiên bản được rà soát; số dòng có thể thay đổi sau khi chỉnh sửa code. Đường dẫn BE tính từ thư mục `self-storage-be`; các đường dẫn bắt đầu bằng `self-storage-fe` tính từ thư mục workspace.

## 7. Sửa đối chiếu thời điểm hoàn tất gia hạn (19/10/2026)

- Hồ sơ `eaaa2225-d049-4844-87dc-94fdc2bb9d6a` bị chặn vì thời điểm trạng thái hoàn tất là `02:44:09.647921`, còn sự kiện hoàn tất là `02:44:09.679642` (UTC ngày 15/10/2026). Hai thời điểm được lấy riêng trong cùng lệnh nhưng bộ xác minh cũ yêu cầu bằng nhau tuyệt đối.
- Lệnh hoàn tất mới dùng một mốc duy nhất, chuẩn hóa đến micro giây để khớp độ chính xác lưu trữ MySQL, cho cả trạng thái và sự kiện.
- Hồ sơ cũ có hai mốc khác nhau chỉ được chấp nhận khi đúng một kết quả lệnh `d3_complete` thành công liên kết đúng hồ sơ, người hoàn tất, lần khách đến, mã sự kiện và cả hai mốc đã lưu. Vẫn kiểm tra điều khoản được khách chấp nhận và bằng chứng kỳ thuê như trước.
- Không dùng khoảng dung sai thời gian tùy ý, không sửa lịch sử hoặc ngày thuê trong database và không thay đổi FE. Kết quả lệnh thiếu, không khớp hoặc mơ hồ vẫn bị chặn.
- Thay đổi này không cần migration riêng. Đã khởi động lại BE ngày 19/10/2026 lúc 21:15 để nhận bản sửa; FE và BE trả HTTP 200. Không chạy kiểm thử tự động trong lượt sửa này.

## 8. Hoàn thiện trải nghiệm hỗ trợ (19/10/2026)

- Giữ riêng nội dung và các mã tệp đã tải lên của từng hồ sơ, từng tab Khách hàng/Quản lý trong phiên hỗ trợ. Đổi tab hoặc gửi ở tab còn lại không làm mất bản nháp; chỉ xóa bản nháp của cuộc trao đổi đã gửi thành công. Không lưu nội dung vào bộ nhớ trình duyệt lâu dài.
- Sau khi gửi tin, tìm trang chứa mã tin nhắn BE trả về; nếu các tin khác đến đồng thời, tiếp tục đối chiếu mã tin. Khi báo đã xử lý, mở trang cuối của cuộc trao đổi với khách để xem kết quả vừa gửi.
- Thông báo hỗ trợ của nhân viên/quản lý truyền mã hồ sơ tới màn hình hỗ trợ để mở đúng chi tiết qua API có kiểm tra quyền. Yêu cầu mở được tiêu thụ sau khi xử lý, không tự mở lại khi quay về trang; bấm lại thông báo vẫn tải lại hồ sơ.
- Giữ nguyên bố cục, CSS và màu sắc. Không thay đổi các quy tắc phân công, chuyển bộ phận hay trạng thái chờ bổ sung trong lượt này. Không chạy kiểm thử tự động.

## 9. Đơn giản hóa thao tác ký gia hạn của nhân viên

- Giao diện chia ba bước: ghi nhận khách đến, ghi nhận thanh toán còn lại và hoàn tất gia hạn. Báo sự cố được tách thành thao tác phụ.
- Tiền mặt: BE tự sinh mã biên lai dạng `TM-...` sau khi kiểm tra lệnh gửi lại; không yêu cầu nhân viên nhập mã. Mã được lưu cùng khoản thu và sự kiện để đối chiếu. Biên lai tải lên và xác nhận thực thu vẫn bắt buộc. Chuyển khoản vẫn cần mã giao dịch ngân hàng.
- Hoàn tất: kiểm tra thanh toán đủ và chứng từ đã lưu qua `requireFullyPaid`; không yêu cầu tải thêm hợp đồng/minh chứng hoặc xác nhận danh tính lần nữa. Không tự ghi giả xác nhận danh tính. Trường xác nhận danh tính và hợp đồng trong payload cũ là tùy chọn; nếu có cung cấp thì vẫn kiểm tra hợp lệ.
- Giữ điều kiện đã ghi nhận khách đến, quyền nhân viên, phiên bản hồ sơ, hạn ký và an toàn giữ chỗ/thu hồi. Thiếu chứng từ hoặc còn khoản phải thu vẫn không được hoàn tất.
- Không sửa các chứng từ hoặc hợp đồng đã lưu của hồ sơ cũ. Không cần migration mới cho thay đổi này.
