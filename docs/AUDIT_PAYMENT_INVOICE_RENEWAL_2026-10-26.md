# Rà soát task Payment, Invoice và Renewal

Cập nhật nguồn hỗ trợ AI: đã thay FAQ riêng bằng catalog hướng dẫn công khai dùng chung, có endpoint đọc công khai và tham chiếu chức năng Customer. Gemini chỉ chọn mục nguồn; backend không trả văn bản tự sinh. Bot legacy không còn các cam kết gạch nợ 30 giây, thanh toán định kỳ hoặc phí trễ 50%; thiếu nguồn trả không biết/đề xuất Nhân viên. Không nạp cấu hình nội bộ hoặc hồ sơ riêng vào AI. Xem [SUPPORT_AI_PUBLIC_SOURCES.md](SUPPORT_AI_PUBLIC_SOURCES.md).

Cập nhật quy tắc ngày gia hạn: FE không còn chọn ngày tùy ý; backend xác định ngày báo giá hợp lệ. Khi hoàn tất ký, chưa quá hạn thì nối tiếp kỳ cũ, đã quá hạn thì bắt đầu từ ngày ký; không cộng setup. Kỳ thực tế được lưu bằng chứng cứ hoàn tất riêng, giữ nguyên báo giá/phân bổ tiền. Bảng kê gia hạn loại ngày ký khỏi khoảng tính phí quá hạn. Chi tiết: [RENEWAL_EFFECTIVE_PERIOD.md](RENEWAL_EFFECTIVE_PERIOD.md). Các nhận xét về ngày trong bản audit gốc bên dưới là ảnh chụp trước lần sửa này.

Cập nhật nhánh lỗi chuẩn bị ký sau thu tiền: đã bổ sung thử lại có giới hạn, trạng thái chờ cơ sở xử lý và lệnh Manager thử lại/duyệt hoàn kèm chứng cứ. Staff khác người duyệt thực hiện hoàn; không cần tạo giả operation state đã xác minh. Luồng thu hồi chỉ đi tiếp khi tiền đã đối soát và giữ đường xử lý hoàn sau thu hồi. Chi tiết và giới hạn kiểm chứng: [RENEWAL_PREPARATION_RESOLUTION.md](RENEWAL_PREPARATION_RESOLUTION.md). Các kết quả test trong bản audit gốc bên dưới không phải xác minh cho thay đổi mới này; lần này không chạy test theo yêu cầu.

Ngày rà soát: 26/10/2026. Code đối chiếu: FE `e6325c1`, BE `1515084`, sau merge develop.

Cập nhật sau rà soát: đã bỏ lựa chọn thanh toán 100% cho gia hạn mới ở FE và chặn tạo báo giá, gửi/sửa yêu cầu bằng báo giá 100% cũ, mở thanh toán 100% mới ở BE. Phương án trên giao diện là trả trước 20%, phần còn lại thanh toán khi ký. Dữ liệu và xử lý đối soát giao dịch cũ được giữ lại; các nhận xét bên dưới mô tả ảnh chụp code lúc rà soát, gồm cả rủi ro đối với dữ liệu 100% đã tồn tại.

Cập nhật tiếp: đã gỡ endpoint/service approve/reject mới từ develop, gỡ các nút duyệt trực tiếp ở Staff và khôi phục nút hoàn tất về form ký gia hạn có kiểm tra điều kiện. Những thay đổi approve/reject của context legacy được đưa về hành vi trước merge; không còn tự kéo dài hợp đồng khi duyệt. Phát hiện số 1 bên dưới là ghi nhận tại thời điểm audit, trước lần gỡ này.

Cập nhật loại bỏ tính tiền FE legacy: đã gỡ công thức giá/giảm giá, chia khoản trả trước, phí trễ và tổng phải thu khỏi luồng renewal/overdue legacy, kể cả đoạn tự ghi lại khoản trả trước/còn lại khi tải localStorage. Legacy hiển thị số đã lưu; thiếu số hoặc tổng hợp chưa có nguồn xác minh thì hiển thị cần đối soát. Các lệnh tạo/sửa báo giá, thu tiền/hoàn tất gia hạn và thu/điều chỉnh phí quá hạn cục bộ bị chặn; người dùng thực hiện qua luồng API. Không ghép ID legacy vào API hoặc tự chuyển các số tiền legacy sang dữ liệu backend. Công thức của booking/check-in ngoài phạm vi thay đổi này. Chỉ chạy typecheck; không chạy test theo yêu cầu.

Cập nhật lịch sử thanh toán khách: giao dịch gia hạn lấy trạng thái, số đã hoàn và quyền xem biên lai từ statusSummary.transactions của backend, ghép bằng paymentId. Event cũ chỉ bổ sung metadata; không chứng nhận PAID. Bản ghi thiếu projection hiển thị Chưa đối soát và không mở biên lai. Projection luôn được ưu tiên hơn event/raw PAID, bất kể timestamp. Lịch sử có thao tác tải lại và làm mới khi quay lại cửa sổ; biên lai dùng bản ghi hiện hành sau làm mới. Các thẻ tổng hợp hiển thị số giao dịch thay vì FE cộng tiền thuê/tiền hoàn. Bố cục biên lai giữ nguyên. Không chạy test theo yêu cầu.

Phạm vi thực hiện hiện tại: đọc code gia hạn, quá hạn, thanh toán/biên lai liên quan; đọc thêm điểm thiết lập ngày khi nhận kho để đối chiếu yêu cầu leader. Chưa thay đổi logic nghiệp vụ. Những thay đổi xóa tài liệu đang có trong working tree BE được giữ nguyên.

## Đối chiếu yêu cầu

| Yêu cầu | Kết quả | Phần còn thiếu |
| --- | --- | --- |
| Tách PaymentStatus và InvoiceStatus | Đã có mô hình đọc tách riêng trong gia hạn; chưa đồng bộ mọi nơi | Lịch sử thanh toán khách hàng và code legacy còn dùng trạng thái cũ. Không nhất thiết phải có enum tên InvoiceStatus để đạt yêu cầu; điều cần đảm bảo là dữ liệu và điều kiện chuyển trạng thái độc lập. |
| Chưa thanh toán, đang xử lý, đã thanh toán, thất bại, đã hoàn tiền, hết hạn | Projection giao dịch gia hạn đã có đủ các nhóm này, thêm chờ đối soát và hoàn một phần | Chưa áp dụng thống nhất cho lịch sử khách hàng/legacy. Trạng thái khoản phải thu có tập riêng; giao dịch thất bại không đồng nghĩa hóa đơn bị hủy hoặc hết nợ. |
| Một nguồn backend tính tiền, bỏ FE tính lại ở legacy | Chưa hoàn tất | Backend mới có RenewalRentCalculation và ledger/bảng kê; FE legacy vẫn tự tính giá, giảm giá, chia khoản trả trước, phí trễ. |
| Rà ngày bắt đầu, nhận kho, hết hạn, gia hạn, quá hạn | Có nền tảng chuẩn hóa, còn lỗi tích hợp | FE cho chọn ngày gia hạn tùy ý nhưng BE submit bắt buộc nối tiếp kỳ cũ; một chỗ hiển thị kỳ gia hạn bắt đầu từ ngày hết hạn cũ. Đường approve ghi ngày kết thúc trực tiếp, bỏ qua quy ước lưu ngày. |
| Ngày bắt đầu hợp đồng không mặc định bằng ngày thanh toán | Luồng startSetup hiện có logic theo bàn giao | termsEffectiveAt được gán khi bắt đầu bàn giao/setup; startDate là ngày bắt đầu tính tiền sau setup. Cần thống nhất tên hiển thị và kiểm thử payment/handover/setup diễn ra khác ngày. Đặt mới/check-in ngoài phạm vi sửa gia hạn/quá hạn đã chốt. |
| Gia hạn không cộng setup miễn phí | Đạt ở đường hoàn tất gia hạn chuẩn | Kỳ mới nối từ endExclusive của kỳ cũ và không tạo setup mới. Cần giữ điều kiện này cho mọi đường hoàn tất, đặc biệt approve. |
| No-show, hết hạn thanh toán, hết kho, hoàn tiền | Đã có nhiều nhánh, chưa thể chốt hoàn thành | Luồng trả 100% sau merge vẫn bị nhận diện như deposit; đường approve/reject chưa dùng các điều kiện tài chính, giữ kho và xử lý hoàn tiền tương ứng. |

## Các việc cần sửa theo ưu tiên

### 1. Chặn đường approve/reject bỏ qua workflow chuẩn - ưu tiên cao

Vị trí: `self-storage-be/src/main/java/com/storagehub/service/renewal/RenewalWorkflowService.java:368` và `:390`; endpoint trong `api/renewal/ManagerRenewalController.java`.

- approve gán trực tiếp contractEndDate, đánh dấu renewal completed và operation state completed.
- Không gọi requireFullyPaid, kiểm tra hạn ký, kiểm tra kỳ thuê đã chấp nhận, kiểm tra giữ kho/thu hồi như RenewalOperationService.complete.
- Không có kiểm tra phase/terminal hoặc expectedVersion ở đường này. Có nguy cơ duyệt lại yêu cầu cũ làm ghi lại ngày hết hạn cũ sau một kỳ gia hạn khác.
- Ngày newEndDate của điều khoản là ngày cuối sử dụng; đường chuẩn dùng period.storeEnd để tôn trọng dữ liệu inclusive/exclusive. approve bỏ qua chuyển đổi đó.
- reject chỉ đổi trạng thái; không thực hiện giải phóng hold/slot, điều chỉnh khoản phải thu hoặc mở xử lý hoàn tiền tương ứng.
- Các kiểm tra quyền đọc ở reads.detail cuối hàm không thay thế kiểm tra quyền ghi trước khi thực hiện lệnh.

Hướng sửa: dùng một đường hoàn tất có đầy đủ điều kiện cho mọi phương án thanh toán; nếu tiếp tục có duyệt/từ chối thì phải quy định rõ giai đoạn được phép, quyền ghi, khóa/version và xử lý tài chính/hold đi kèm.

### 2. Phân biệt trả trước một phần và trả toàn kỳ trong xử lý ngoại lệ - ưu tiên cao

Chuỗi code hiện tại:

1. `RenewalWorkflowService.java:333`: fullPayment=true đặt renewalDepositAmount bằng toàn bộ netRent, remainingRentalAmount=0.
2. `FinancialContractSpecifications.java:104`: trường hợp này ghi một obligation loại RENEWAL_DEPOSIT cho toàn bộ tiền thuê.
3. `RenewalVnpaySettlementService.java:203`: chỉ kiểm tra renewalDepositAmount>0 để chọn state.deposit, thay vì nhận diện thanh toán toàn kỳ.
4. `RenewalDepositSettlementService.java:82`: khi quá hạn ký và đến ngày thu hồi thứ 8, nếu không bị chặn bởi kiểm tra trách nhiệm/hoàn tiền, nhánh này có thể ghi FORFEITED với toàn bộ số tiền payment.

Đây là đường có thể đi tới theo code, chưa phải sự cố đã tái hiện trên dữ liệu thật. Cần tách căn cứ xử lý tiền thuê trả đủ khỏi chính sách khoản trả trước một phần; không tự suy ra toàn bộ tiền thuê được phép không hoàn chỉ vì đang dùng tên/loại DEPOSIT.

Ngoài ra `CustomerRenewalRequestModal.tsx:174` đang ghi thanh toán 100% thì hợp đồng tự động gia hạn ngay, trong khi settlement chuẩn vẫn đưa sang bước ký. Cần thống nhất hành vi và lời hướng dẫn.

### 3. Loại bỏ phép tính tiền và ghi nhận thành công ở FE legacy

Vị trí chính:

- `self-storage-fe/src/store/StorageHubContext.tsx:2976`: requestRenewal tự tính giá gốc, giảm giá, tổng tiền.
- Cùng file `:3034`: updateRenewalRequest tính lại các số tiền.
- Cùng file `:3180`, `:3261`: code payRenewal/completeRenewalAtFacility còn tính phí trễ, số phải thu và tạo bản ghi PAID tại FE cho dữ liệu legacy phù hợp.
- `self-storage-fe/src/views/customer/CustomerApp.tsx:4037`, `:4075`: modal legacy tự tính báo giá, giảm giá và các phí dự kiến.
- `self-storage-fe/src/domain/renewalPricing.ts`: vẫn có hàm chia số tiền tại FE.

Các modal legacy này có điều kiện `!isApiAuthenticated()`, nên không kết luận rằng luồng API đang sử dụng trực tiếp những tổng tự tính đó. Tuy vậy yêu cầu leader nói rõ loại bỏ tính lại ở legacy, nên phần này vẫn chưa đạt.

Hướng sửa: chuyển legacy sang báo giá/bảng kê backend, hoặc ngừng các thao tác tài chính legacy và chỉ cho xem dữ liệu cũ. Không giữ fallback lấy giá/tháng nhân số tháng để tạo tổng chính thức.

### 4. Đồng bộ lịch sử thanh toán khách hàng với projection trạng thái

Vị trí: `self-storage-fe/src/services/customerPaymentHistory.ts:187`, `:225`; `views/customer/CustomerPaymentHistory.tsx:80`.

- Lịch sử gia hạn bổ sung dựng trạng thái PAID từ event CASH/outcome SUCCESS/paidAt.
- Chưa dùng statusSummary.transactions để hiển thị trạng thái hiện tại sau đối soát/hoàn tiền.
- Bộ nhãn gom nhiều trạng thái về "Chưa ghi nhận tiền"; chưa phân biệt đủ đang xử lý/hết hạn/chờ đối soát cho giao dịch gia hạn.
- Vì chỉ lấy payment events, một payment event cũ có thể vẫn được hiển thị PAID sau khi giao dịch đó đã hoàn tiền; tổng các khoản PAID trên trang cũng cần làm rõ là số đã thu hay số thu ròng.

Không kết luận event SUCCESS tự nó là minh chứng giả: event có thể được backend tạo sau xác minh. Vấn đề là màn hình chưa theo nguồn trạng thái hiện hành và vòng đời hoàn tiền.

### 5. Đồng bộ ngày nối tiếp và cách hiển thị kỳ gia hạn

- `CustomerRenewalRequestModal.tsx:128` cho chọn ngày bắt đầu tùy ý; `RenewalWorkflowService.java:331` có thể tạo quote cho ngày đó.
- `service/rental/period/RenewalPeriodCompatibility.java:17` lại yêu cầu extensionStartDate chính xác bằng endExclusive kỳ cũ khi submit/hoàn tất. Vì vậy quote có thể hiện được nhưng submit bị từ chối nếu khách chọn lệch ngày.
- `RenewalDetail.tsx:131` ghi "Kỳ gia hạn" từ oldEndDate; trong phần điều khoản `:163` lại dùng extensionStartDate. Hai đoạn thể hiện khác nhau.
- Luồng quá hạn chuẩn dùng RentalPeriodResolver và RenewalOverdueTimeline: ngày quá hạn đầu là ngày sau ngày cuối sử dụng; mốc chốt gia hạn 17:00 ngày 7, mốc thu hồi 08:00 ngày 8 theo giờ Việt Nam.

Hướng sửa: lấy ngày nối tiếp từ backend, bỏ lựa chọn không hợp lệ; phân biệt rõ ngày cuối kỳ cũ, ngày đầu kỳ mới, hạn thanh toán và hạn ký. Không cộng ngày setup cho gia hạn.

### 6. Khóa phương án trả từng tháng theo phạm vi đã chốt

Ngoài checklist leader, đây là yêu cầu đã chốt trước đó: hệ thống hiện chưa áp dụng thanh toán từng tháng.

- `api/renewal/RenewalCommands.java:24` vẫn công bố MONTHLY trong options.
- quote vẫn nhận paymentPlan=MONTHLY; nhánh tính toán và installment endpoint/service vẫn tồn tại.
- `RenewalStatusProjection` trả UNKNOWN/UNSUPPORTED_PAYMENT_PLAN cho điều khoản monthly.

Vì vậy chưa thể coi phương án này đã bị loại khỏi phạm vi khả dụng của backend. Cần chặn yêu cầu mới/công bố lựa chọn mới; nếu phải đọc dữ liệu cũ thì giữ khả năng đọc riêng, không mở thu tiền mới theo tháng.

## Những phần nên giữ

- RenewalStatusSummary tách workflow, invoice, transaction và refund.
- RenewalTransactionStatus/RenewalTransactionReader không xác nhận SUCCEEDED chỉ từ raw PAID hoặc mã tham chiếu; yêu cầu provenance và receipt/allocation phù hợp.
- Tiền bảo đảm ban đầu được giữ tiếp; gia hạn không tạo thêm obligation SECURITY_DEPOSIT trong FinancialContractSpecifications.accepted.
- RenewalRentCalculation tính giá gốc, giảm giá một lần và tiền thuê sau giảm; khoản đã trả trước là phần của tiền thuê, không cộng thêm vào tổng tiền thuê.
- Bảng kê ký gia hạn trừ khoản thu đã phân bổ; FE API mới hiển thị số backend trả về.
- RenewalPaymentExpiryService đối soát giao dịch chưa rõ kết quả trước khi đóng yêu cầu và giải phóng quyền giữ kho.
- RenewalOperationalGuard kiểm tra xung đột gian kho, bảo trì, đặt/thuê khác và thu hồi. Hết kho trong gia hạn cần hiểu là không còn quyền tiếp tục gian kho hiện tại, không chỉ là số gian trống toàn cơ sở bằng 0.
- Quy trình hoàn tiền hiện có phân biệt phê duyệt hoàn và thực hiện hoàn có chứng từ; chỉ khoản EXECUTED mới được projection tính là đã hoàn.

## Tiêu chí kiểm tra bổ sung trước khi báo hoàn thành

1. Không thể duyệt hoàn tất hoặc ghi lùi ngày hết hạn qua yêu cầu chưa trả đủ/đã đóng/yêu cầu của kỳ cũ.
2. Thanh toán 100% được nhận diện đúng và có chính sách xử lý no-show/hoàn tiền riêng; không tự động áp dụng forfeiture của khoản trả trước một phần.
3. Không màn hình hoặc context legacy nào tạo tổng chính thức, giảm giá hay giao dịch PAID tại FE.
4. Cùng một giao dịch cho kết quả thống nhất trên chi tiết gia hạn, lịch sử khách, lịch sử quản lý và biên lai, kể cả pending/refund/expiry.
5. Thanh toán ngày A, bàn giao ngày B, hết setup ngày C: ngày hiệu lực, ngày nhận kho và ngày tính tiền được hiển thị đúng ý nghĩa. Gia hạn không tạo setup mới.
6. Báo giá và submit cùng chấp nhận một kỳ nối tiếp, đúng cả dữ liệu ngày hết hạn inclusive và exclusive.
7. Nhánh không còn quyền giữ kho sau khi đã thu tiền đi vào đối soát/xử lý hoàn, không tự đánh dấu hoàn tất.
8. API không cho tạo phương án thanh toán từng tháng mới.

## Kiểm chứng

- Đã chạy lại 4 file test FE liên quan trình bày trạng thái, nút thanh toán và lịch sử khách: 54 test pass.
- Đã chạy lại 10 lớp test BE về tiền, trạng thái, đối soát/phân bổ, hết hạn, forfeiture và quy ước ngày: 54 test pass, không lỗi/skip; BUILD SUCCESS. Log: `.local/audit-renewal-be-tests.log`.
- Log kiểm tra sau merge trước đó: FE 1.234 test pass, 2 skip; BE 1.057 test gồm 1.007 pass, 50 skip, không lỗi. Các kết quả này không chứng minh các đường tích hợp mới bên trên đã được bao phủ.
- Chưa chạy giao dịch gateway thật hoặc thao tác thu/hoàn tiền trên DB dùng chung. Các phát hiện là đối chiếu logic và kiểm thử tự động hiện có, không phải xác nhận một sự cố tài chính đã xảy ra.
