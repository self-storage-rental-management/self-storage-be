# Kịch bản nghiệm thu Task 3 — Quách Thế Dương

Ngày lập: 26/10/2026. Phạm vi: Payment, Deposit, Invoice, Renewal, Date.

**Trạng thái ban đầu: tất cả ca Chưa chạy.** Đây là kế hoạch nghiệm thu thủ công, không phải báo cáo test pass. Không chạy thêm test hoặc sửa dữ liệu trong lượt soạn tài liệu này.

## 1. Chuẩn bị

- FE: http://localhost:8443. BE/Swagger: http://localhost:8080/swagger-ui.html. Kiểm tra lại dịch vụ trước buổi nghiệm thu.
- Đồng bộ bản FE/BE; ghi commit và tình trạng sửa local. Kiểm tra Flyway history có migration nền, V2026102601 và V2026102602. Log khởi động local ngày 26/10/2026 đã ghi áp thành công đến V2026102602; môi trường khác phải kiểm tra riêng.
- Customer A sở hữu hồ sơ; Customer B dùng kiểm tra truy cập chéo. Staff đúng cơ sở có quyền check-in/thu tiền; Manager phân gian; Business có quyền sửa chính sách; người thực hoàn có quyền refunds:execute theo cấu hình.
- Cơ sở hoạt động, có kho phù hợp, lịch hẹn hợp lệ, gói giá còn hiệu lực; VNPay Sandbox, ledger, hold/calendar và lưu file đã cấu hình.
- Chuẩn bị hợp đồng đã ký có điều khoản trả trong setup, biên lai test và ảnh kiểm kê không chứa thông tin cá nhân thật. Không chuyển tiền thật cho buổi demo.
- Ghi thời gian theo Asia/Ho_Chi_Minh. Chọn dữ liệu còn hạn tại ngày nghiệm thu; không dùng một hồ sơ đã qua cutoff rồi kỳ vọng gia hạn thành công.
- Thu tiền khi check-in là thu trước bàn giao. “Cọc PAID để check-in” là khoản trả trước khi đặt kho, khác cọc bảo đảm.
- Không chỉ sửa cột status thành PAID hoặc sửa một ngày trong DB để vượt guard. Dữ liệu đặc biệt phải dùng fixture đầy đủ, cô lập trên DB test và có bằng chứng tài chính/timeline phù hợp.
- Chưa có fixture để kiểm tra lỗi/boundary thì ghi **Blocked — thiếu dữ liệu**, không ghi Pass. Việc tạo fixture cần được người phụ trách môi trường hỗ trợ.

## 2. Bộ dữ liệu đề xuất

Tạo các hồ sơ độc lập, không dùng một Rental vừa trả kho vừa gia hạn.

| Mã dữ liệu | Nội dung | Dùng cho |
| --- | --- | --- |
| D01 | Reservation chưa thanh toán, có quote hợp lệ | Chặn check-in |
| D02 | Reservation đã trả trước PAID hợp lệ, phân gian; gói 3 tháng, setup 3 ngày | Check-in, timeline, snapshot |
| D03 | Reservation gói setup 0 ngày | Timeline không setup |
| D04 | Rental chưa hết kỳ, được phép gia hạn, không tranh chấp/công nợ chặn | Gia hạn nối tiếp |
| D05 | Rental overdue, còn trước cutoff, chưa thu hồi và còn quyền tiếp tục kho | Gia hạn overdue |
| D06 | Rental mới trong setup, đã thu đủ, có điều khoản trả setup đã chấp nhận | Hoàn tiền setup |
| D07 | Fixture riêng: thiếu snapshot/proof, sai binding/số tiền, đã hoàn | Nhánh chặn tài chính/lịch sử |
| D08 | Fixture trả thực tế trước/tại/sau setupEndsAt; qua ngày/gần nửa đêm/cuối tháng | Biên thời gian |

Bộ số tiền minh họa cho D02/D06: đơn giá 2.000.000 đ/tháng, 3 tháng, giảm giá 0%, tiền thuê 6.000.000 đ, trả trước 1.200.000 đ, tiền thuê còn lại 4.800.000 đ, cọc bảo đảm 2.000.000 đ; phải thu khi check-in 6.800.000 đ.

Chỉ dùng bộ số này nếu cấu hình và quote backend thực sự trả đúng. Nếu gói test có giá/tỷ lệ khác, ghi số backend vào biên bản rồi tính kết quả mong đợi tương ứng; không nhập số minh họa để ép qua bước.

## 3. Trình tự nghiệm thu đề xuất

1. Dùng D01 kiểm tra chưa PAID bị chặn, sau đó dùng D02 chứng minh PAID hợp lệ đi tiếp.
2. Chạy check-in D02 đến bàn giao, đối chiếu khoản thu và timeline; kiểm tra snapshot trước/sau đổi policy.
3. Chạy D03 để kiểm tra setup bằng 0.
4. Chạy D04 và D05 đến hoàn tất gia hạn; kiểm tra invoice/payment và ngày hiệu lực.
5. Chạy D06 để trả trong setup, tính tiền hoàn và ghi chứng từ sandbox/test.
6. Dùng fixture riêng để kiểm tra các nhánh chặn, gửi lại lệnh và mốc biên.
7. Tổng hợp kết quả theo 5 tiêu chí DONE. Không lấy việc chạy được demo thành công thay cho kiểm tra nhánh chặn.

## 4. Các ca nghiệm thu

### T3-01 — Contract trạng thái FE/BE

- Bao phủ: yêu cầu 1, 9; DONE 4.
- Chuẩn bị: hồ sơ có trạng thái trước thanh toán, sau trả trước, đang ký và hoàn tất.
- Thực hiện: mở chi tiết reservation/rental/renewal và lịch sử thanh toán; dùng Network lưu response tương ứng. So từng mã với enum/type FE và response BE.
- Mong đợi: mỗi đối tượng dùng đúng contract của nó; nhãn tiếng Việt có thể khác raw code. Không bắt payment, invoice và renewal cùng một mã trạng thái.
- Kiểm tra riêng: giao dịch FAILED không tự biến invoice thành CANCELLED; reservation đủ điều kiện phân kho không tự chứng minh payment PAID; raw PaymentStatus và read model giao dịch có thể dùng mã khác nhau theo contract.
- Bằng chứng: ảnh FE và response cùng hồ sơ/thời điểm, paymentId, renewalId; ghi riêng payment status, invoice status và workflow status.

### T3-02 — Chặn check-in khi khoản trả trước chưa PAID

- Bao phủ: yêu cầu 2; DONE 1.
- Dữ liệu: D01; Staff đúng quyền để lỗi không bị che bởi thiếu quyền.
- Thực hiện: thử lên lịch/tiếp tục check-in trên FE. Dùng Swagger gọi thao tác tương ứng với body hợp lệ. Với fixture check-in cũ, thử trực tiếp thu phần còn lại/bàn giao khi trả trước chưa hợp lệ.
- Mong đợi: backend từ chối vì điều kiện thanh toán; không chỉ ẩn nút ở FE. Không tạo khoản thu mới, bàn giao, credential hoặc Rental có hiệu lực nhờ bypass.
- Bằng chứng: response lỗi, thông báo FE và trạng thái trước/sau. HTTP 401/403 hoặc lỗi body không đủ chứng minh guard PAID đã hoạt động.

### T3-03 — PAID phải đúng khoản và có chứng cứ

- Bao phủ: yêu cầu 2, 3; DONE 1.
- Dữ liệu: D02 và từng biến thể D07 độc lập.
- Thực hiện: thanh toán D02 bằng luồng Sandbox có callback hợp lệ. Sau đó thử riêng các trường hợp payment PENDING/FAILED, SIMULATED không được phép, sai reservation, sai số tiền, đã hoàn, thiếu proof; chỉ có ảnh hoặc transaction reference.
- Mong đợi: D02 được đi tiếp khi các điều kiện khác đủ; các biến thể không hợp lệ bị chặn, không phát sinh bàn giao/thu tiền. Không suy PAID từ return URL hoặc ảnh đơn lẻ.
- Bằng chứng: paymentId, reservationId, số tiền quote/payment, kết quả xác minh và response cho từng biến thể. Không lưu hash secret/token vào minh chứng.

### T3-04 — Thu phần còn lại trước bàn giao

- Bao phủ: yêu cầu 3; hỗ trợ DONE 1 và 4.
- Dữ liệu: D02 đã PAID, đủ điều kiện ký/thu tiền theo các bước hệ thống.
- Thực hiện: Staff mở bảng kê, xác minh khách, upload hợp đồng theo flow, ghi nhận thực thu tiền mặt với biên lai đúng số tiền rồi bàn giao. Trước khi thu đủ, thử thao tác bàn giao.
- Mong đợi: trước thu đủ bị chặn; sau thu đủ được đi tiếp. Theo bộ số mẫu, chỉ thu thêm 6.800.000 đ; tổng thực thu sau check-in là 8.000.000 đ. Không cộng lại 1.200.000 đ trả trước hoặc 2.000.000 đ cọc bảo đảm ngoài bảng kê.
- Staff xác nhận thực thu kèm biên lai, không phát sinh bước Manager duyệt biên lai tiền mặt.
- Bằng chứng: quote, bảng kê, payment/receipt và ledger nếu có quyền; so sánh trước/sau thu. Refresh và gửi lại cùng lệnh/key không được ghi thu lần hai.

### T3-05 — Timeline bàn giao, setup và kỳ thuê trả tiền

- Bao phủ: yêu cầu 4, 5, 10; DONE 2.
- Dữ liệu: D02; thời điểm đặt/thanh toán khác thời điểm bàn giao.
- Thực hiện: ghi thời điểm bàn giao H và số ngày setup snapshot S, số tháng M. Sau bàn giao đọc Rental/CheckIn/Reservation bằng API và đối chiếu FE.
- Mong đợi theo logic hiện tại: setupStartedAt gắn bàn giao thực tế; ngày bắt đầu tính thuê = ngày H theo giờ Việt Nam + S ngày lịch; setupEndsAt là 00:00 ngày bắt đầu tính thuê; Rental.startDate bằng ngày này; contractEndDate = startDate + M tháng.
- Ví dụ H = 05/11/2026 lúc 10:30, S = 3, M = 3: setup kết thúc 08/11/2026 lúc 00:00, startDate = 08/11/2026, contractEndDate = 08/02/2027. Đây là ngày lịch, không kỳ vọng đủ 72 giờ từ 10:30.
- Kiểm tra quy ước endDate inclusive/exclusive trong read model trước khi so ngày hiển thị; không tự cộng/trừ một ngày để làm khớp.
- Bằng chứng: H, S, M, setupStartedAt, setupEndsAt, paidRentalStartsOn nếu response có, startDate/endDate và quy ước ngày. Không chỉ kiểm tra UI ghi “ACTIVE”.

### T3-06 — Setup 0 ngày và biên thời gian

- Bao phủ: yêu cầu 4, 5, 10; DONE 2.
- Dữ liệu: D03 và fixture D08 đầy đủ.
- Thực hiện: bàn giao gói S = 0; kiểm tra thêm bàn giao trước/sau nửa đêm giờ Việt Nam, cuối tháng và kỳ đi qua tháng 2 nếu có fixture.
- Mong đợi: S = 0 thì ngày tính thuê là ngày bàn giao, không tự thêm ngày miễn phí. Ngày lịch tính theo Việt Nam, không lệch vì UTC. Cộng số tháng theo backend, không dùng M × 30 ngày ở FE.
- Khi S = 0, setupEndsAt theo ngày lịch có thể là đầu ngày bàn giao, trước giờ bàn giao; không ép thành H + 24 giờ. Đối chiếu đúng quy tắc đang dùng.
- Bằng chứng: timestamp có offset/UTC và ngày Việt Nam tương ứng. Không có fixture/clock phù hợp thì để Blocked, không đổi giờ máy hoặc sửa rời một cột DB.

### T3-07 — Đổi policy không thay snapshot đơn đã chấp nhận

- Bao phủ: yêu cầu 6; DONE 5.
- Dữ liệu: reservation R1 chấp nhận quote Q1, lưu giá/setup/policy version; chưa bàn giao. Có quyền chỉnh policy trên cơ sở test.
- Thực hiện: cập nhật policy từ P1 sang P2 bằng flow quản trị hợp lệ, đổi giá và số ngày setup; tạo quote Q2 cho một đơn khác. Mở lại R1, thực hiện thanh toán/check-in R1 theo điều kiện của đơn cũ.
- Mong đợi: R1 giữ giá, S và policy version đã chốt; khoản cần thu/timeline bàn giao dựa snapshot của R1. Q2 dùng chính sách mới khi P2 có hiệu lực. Không ghi lại R1 bằng catalog mới.
- Nếu quote Q1 chưa được chấp nhận và đã hết hạn/không còn hợp lệ, hệ thống có thể yêu cầu báo giá mới; không dùng case này thay kiểm tra reservation đã chấp nhận.
- Bằng chứng: Q1/R1 trước và sau đổi policy, Q2 mới, phiên bản và khoản tiền; response check-in R1.

### T3-08 — Hồ sơ cũ thiếu snapshot/chứng cứ

- Bao phủ: yêu cầu 6, 10; DONE 5.
- Dữ liệu: D07 thiếu snapshot có kiểm soát, lưu riêng với hồ sơ hợp lệ.
- Thực hiện: mở reservation rồi thử mở thanh toán, thu phần còn lại hoặc setup khi thiếu dữ liệu bắt buộc.
- Mong đợi: hồ sơ vẫn tra cứu được với cảnh báo; tiền/ngày chưa biết không bị tự điền 0, giá mới hoặc ngày reservation. Thao tác cần dữ liệu thiếu bị chặn để đối soát. Xác nhận nhận kho không tự tạo Rental/hợp đồng thay hồ sơ bàn giao thiếu.
- Bằng chứng: response chứa null/cảnh báo, FE hiển thị và lỗi nghiệp vụ; kiểm tra không có bản ghi dự phòng được tạo.

### T3-09 — Gia hạn chưa quá hạn, không setup mới

- Bao phủ: yêu cầu 7, 10; DONE 3.
- Dữ liệu: D04, ghi kỳ cũ và quy ước ngày kết thúc, gói gia hạn M tháng.
- Thực hiện: lấy quote, gửi yêu cầu, thanh toán trả trước, chọn/đi lịch ký theo flow, Staff ghi nhận đến, thu đủ và upload hợp đồng đã ký rồi hoàn tất. Trước đó thử completion thiếu hợp đồng hoặc thiếu tiền.
- Mong đợi: thiếu tiền/file thì không hoàn tất; thành công thì đầu kỳ mới bằng cuối kỳ cũ theo endExclusive backend, hết kỳ tính đủ M tháng. Không thêm freeSetupDays của gói thuê ban đầu; không đặt lại setupStartedAt/setupEndsAt cho kỳ gia hạn.
- Ngày hiệu lực không bị lấy theo ngày thanh toán nếu khác ngày nối tiếp. FE thống nhất extensionStartDate/actual effective period theo trạng thái hồ sơ.
- Bằng chứng: kỳ cũ, quote, payable-statement, hợp đồng, response completion và Rental sau hoàn tất. Gửi lại cùng lệnh/key không gia hạn/thu tiền hai lần.

### T3-10 — Gia hạn overdue, ngày hiệu lực từ hoàn tất đủ điều kiện

- Bao phủ: yêu cầu 7, 10; DONE 3.
- Dữ liệu: D05 còn hạn; ngày trả trước và ngày hoàn tất khác nhau trong fixture hợp lệ để phân biệt hai mốc.
- Thực hiện: chạy flow gia hạn đến ký/thu đủ/upload hợp đồng; ghi ngày hoàn tất K theo Việt Nam. Nếu sang ngày mới, tải lại payable-statement và dùng ngày hiệu lực backend mới trước completion.
- Mong đợi: đầu kỳ thực tế = ngày K, không phải ngày trả trước, không lùi về ngày hết kỳ cũ, không cộng setup; cuối kỳ = đầu kỳ + M tháng theo quy ước backend. Phí overdue không tính trùng ngày K đã thuộc kỳ mới.
- Thử statement/ngày xác nhận cũ khi đã qua ngày: phải yêu cầu tải lại/đối chiếu, không âm thầm chốt ngày cũ. Qua cutoff hoặc mất quyền tiếp tục kho phải bị chặn dù đã trả tiền.
- Bằng chứng: paidAt, completedAt, quote dự kiến, kỳ thực tế, phí overdue và response nhánh chặn. Nếu không có fixture khác ngày thì phần chứng minh tách ngày thanh toán chưa được coi Pass.

### T3-11 — Invoice/bảng kê và lịch sử thanh toán đúng nguồn

- Bao phủ: yêu cầu 1, 9; DONE 4.
- Dữ liệu: hồ sơ API trước/sau thanh toán và một bản legacy chỉ để đọc.
- Thực hiện: mở bảng kê, từng giao dịch, biên lai và lịch sử; refresh và đối chiếu response backend. Kiểm tra thanh toán chỉ một phần, thất bại/hết hạn và đã hoàn nếu có fixture.
- Mong đợi: số tiền/trạng thái đúng backend tại cùng thời điểm; thất bại một lần không xóa các khoản đã thu hợp lệ. Chưa đủ tiền không tự thành invoice PAID. Thiếu summary phải hiện chưa xác định, không tự dựng invoice từ amount/event.
- Legacy không tự tạo INV-RNW, ngày phát hành/hạn thanh toán hoặc thao tác xác nhận đã thu. Bảng kê được ghi đúng bản chất, không nhận là hóa đơn tài chính đã phát hành nếu backend chưa có.
- Bằng chứng: invoice status, refund status, từng transaction status và số tiền riêng; ảnh/JSON trước/sau. Không yêu cầu các trạng thái này có tên giống nhau.

### T3-12 — Trả kho trong setup và hoàn tiền đúng

- Bao phủ: yêu cầu 8; kiểm tra bổ sung bắt buộc của Task 3.
- Dữ liệu: D06 quote mới, điều khoản đã chấp nhận, thanh toán có chứng cứ; thực trả trước setupEndsAt.
- Thực hiện: Customer yêu cầu hủy/trả sau bàn giao → vào ReturnCase → Staff kiểm kê, xác nhận kho trống → hệ thống trả bảng kê → Customer xác nhận → người có quyền ghi nhận thực hoàn thử nghiệm với biên lai hợp lệ.
- Mong đợi: chưa chuyển hàng và đang chuyển hàng dùng cùng công thức; một khoản phí chấm dứt một tháng theo snapshot; không trừ khoản trả trước lần hai. Theo bộ mẫu: 6.000.000 + 2.000.000 − 2.000.000 = hoàn 6.000.000 đ nếu không phát sinh; có hư hại 300.000 đ hợp lệ thì hoàn 5.700.000 đ.
- Chi phí phát sinh có ghi chú/chứng cứ. Duyệt/đồng ý hoàn chưa được hiển thị đã hoàn thực tế. Chỉ đóng sau số tiền, quyền và biên lai hợp lệ; ghi rõ Sandbox/test, không dùng biên lai test để tuyên bố đã chuyển tiền thật.
- Bằng chứng: điều khoản Q1, tổng thực thu, setupReturnedAt/setupEndsAt, từng khoản quyết toán, chứng từ và lịch sử sau ghi nhận hoàn.

### T3-13 — Chặn quyết toán/hoàn tiền sai và kiểm tra hết setup

- Bao phủ: yêu cầu 8, 10.
- Dữ liệu: từng biến thể độc lập D06/D07/D08.
- Thực hiện: thử thiếu unitVacated, phí hư hại không chứng cứ, thiếu điều khoản snapshot, từng hoàn một phần, sai số tiền, thiếu/sai hồ sơ biên lai hoặc người không đủ quyền.
- Mong đợi: từ chối hoặc yêu cầu đối soát theo lý do tương ứng, không tự lấy chính sách mới để hoàn, không đóng hồ sơ chưa đủ điều kiện.
- Tạo yêu cầu trong setup nhưng nghiệm thu tại/sau setupEndsAt: không áp công thức setup. Mốc quyết định là thời điểm thực trả/Staff nghiệm thu, không phải lúc khách gửi yêu cầu; đúng tại setupEndsAt cũng không còn thuộc khoảng setup.
- Sau một hồ sơ hoàn thành, gửi lại cùng chứng từ/lệnh: không hoàn hoặc thu lần hai. Customer khác không được đọc/ghi chứng từ của hồ sơ này.
- Bằng chứng: lỗi từng biến thể, thời điểm hai phía biên, bảng kê và số lần ghi nhận tài chính trước/sau gửi lại.

## 5. Ma trận bao phủ và điều kiện ký nghiệm thu

| Tiêu chí/yêu cầu | Ca cần đối chiếu |
| --- | --- |
| DONE 1: cọc PAID mới check-in | T3-02, T3-03, T3-04 |
| DONE 2: setup không lệch ngày tính thuê | T3-05, T3-06 |
| DONE 3: renewal không thêm setup | T3-09, T3-10 |
| DONE 4: invoice/payment cùng contract FE/BE | T3-01, T3-04, T3-11 |
| DONE 5: policy không đổi lịch sử reservation | T3-07, T3-08 |
| Yêu cầu 8: trả/hủy trong setup, tiền hoàn | T3-12, T3-13 |

- Được ký DONE khi các ca áp dụng bên trên Pass, có bằng chứng và không còn lỗi chặn trong Task 3.
- Fail: có kết quả trái mong đợi. Blocked: chưa thực hiện được vì thiếu fixture/quyền/cấu hình. Chưa chạy: chưa thực hiện. Các trạng thái này không được tính là Pass.
- Một ca có nhiều biến thể phải ghi kết quả từng biến thể; không đánh Pass toàn ca khi chỉ chạy nhánh thành công.
- Chạy lại ca Fail sau sửa và kiểm tra luồng chịu ảnh hưởng. Lỗi nền từ task khác vẫn ghi nhận và nêu người phụ thuộc, không tự đánh Pass.
- Compile/typecheck, migration thành công hoặc HTTP 200 chỉ là điều kiện sẵn sàng, không thay bằng chứng nghiệm thu nghiệp vụ.

## 6. Biên bản kết quả

- Người thực hiện: …
- Người nghiệm thu: …
- Ngày/giờ, timezone: …
- FE commit + sửa local: …
- BE commit + sửa local: …
- DB/migration version: …
- Gateway: Sandbox / môi trường khác: …

| Ca | Hồ sơ/biến thể | Kết quả thực tế | Trạng thái | Bằng chứng | Lỗi/phụ thuộc |
| --- | --- | --- | --- | --- | --- |
| T3-01 | … | … | Chưa chạy | … | … |
| T3-02 | … | … | Chưa chạy | … | … |
| T3-03 | … | … | Chưa chạy | … | … |
| T3-04 | … | … | Chưa chạy | … | … |
| T3-05 | … | … | Chưa chạy | … | … |
| T3-06 | … | … | Chưa chạy | … | … |
| T3-07 | … | … | Chưa chạy | … | … |
| T3-08 | … | … | Chưa chạy | … | … |
| T3-09 | … | … | Chưa chạy | … | … |
| T3-10 | … | … | Chưa chạy | … | … |
| T3-11 | … | … | Chưa chạy | … | … |
| T3-12 | … | … | Chưa chạy | … | … |
| T3-13 | … | … | Chưa chạy | … | … |

Với mỗi biến thể, thêm dòng và ghi ID reservation/rental/renewal/payment/return tương ứng. Lưu ảnh và JSON đã che token, OTP, bí mật gateway và dữ liệu cá nhân. Có thể đặt trong docs/evidence/task3/<ngay-nghiem-thu>/<ma-ca>/ và dẫn link trong bảng.

Kết luận: **Chưa nghiệm thu**. Người nghiệm thu cập nhật Pass/Fail/Blocked và ký xác nhận sau khi thực hiện, không điền sẵn DONE.
