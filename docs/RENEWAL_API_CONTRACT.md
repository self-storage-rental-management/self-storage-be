# Đặc tả API gia hạn (D2)

## Bổ sung hoàn thiện theo hướng mở rộng — 06/10/2026

- Phản hồi Renewal bổ sung `acceptedTerms` có thể null (đối tượng lồng `RenewalQuoteResponse.Terms`, không dàn phẳng) và `cancellationReason`. Điều khoản chỉ lấy từ phiên bản điều chỉnh/báo giá đã lưu và được Customer chấp nhận; không tính lại theo giá hiện tại. Bản ghi cũ không có workflow trả null. Lược đồ danh sách/chi tiết/lệnh nhất quán; không bổ sung API tra cứu báo giá công khai riêng.
- Dữ liệu tổng hợp kiểm tra liên kết Rental/Customer của báo giá đã chấp nhận và tính nhất quán giữa amount/newEndDate của yêu cầu đã lưu. Dữ liệu sai lệch trả 409, không sửa bản ghi hoặc làm lộ tài nguyên không liên quan.
- Lý do hủy lấy từ lý do đã được lưu trong workflow hiện có. Không suy ra cancelledBy/cancelledAt từ requestedBy/người duyệt/updatedAt; không thêm các trường này khi chưa có bằng chứng có thẩm quyền.
- `FinancialSource.consistentThroughApproval()` mặc định false. Chủ nguồn phải triển khai giao thức nhất quán dùng chung bằng khóa/phiên bản trước khi bật hỗ trợ; chỉ checkedAt không bảo đảm an toàn đồng thời. Phản hồi đọc có thể là COMPLETE trong khi duyệt vẫn bị chặn do thiếu giao thức nhất quán.
- Giao diện tích hợp tùy chọn `ApprovalLifecycleSource.ready(Rental)` là điều kiện bắt buộc cho APPROVE và APPROVE trong allowedActions, bên cạnh policy/pricing/eligibility/tài chính đầy đủ/giữ chỗ nguyên tử. Không cài đặt lớp triển khai chạy thực tế. Trạng thái sẵn sàng do chủ nguồn xác nhận có nghĩa là liên kết thanh toán, đối soát hạn thanh toán/hết hạn và chuyển giao hold/open-slot tương ứng đã thực sự được triển khai; đây không phải công tắc FE hoặc môi trường.
- Phụ thuộc duyệt còn thiếu xuất hiện trong disabledReasons; từ chối và hủy yêu cầu đang chờ có workflow đã xác minh vẫn độc lập. Duyệt không kéo dài ngày Rental. Bản ghi đã duyệt không bị viết lại bởi bổ sung này.
- Giá/cọc/phần còn lại đã chấp nhận hiển thị cho người dùng là nghĩa vụ phải trả, không phải bằng chứng đã thanh toán. FE hiển thị điều khoản lịch sử đã chấp nhận và giữ đúng trạng thái null/UNKNOWN khi chưa có dữ liệu.

Việc công bố policy chung, giá/điều kiện gói thuê/làm tròn, nguồn ngày/lịch/Recovery, tài chính, giao thức giữ chỗ giữa các bên cùng ghi, vòng đời D3 và migration MySQL được chủ nguồn duyệt vẫn là các phụ thuộc tích hợp bên ngoài. Bổ sung này không tự chọn vòng đời yêu cầu hết hạn hoặc chính sách làm tròn thay cho chủ nguồn khác.

Đường dẫn gốc `/api`; JWT Bearer; dùng ApiResponse/PageResponse/correlationId chuẩn. Không có dữ liệu mẫu chạy thực tế/policy mặc định, nguồn tài chính giả hoặc endpoint HTTP không tồn tại.

## Thao tác

| Phương thức/đường dẫn | Kết quả | Điều kiện tiên quyết |
|---|---|---|
| GET /customer/rentals/{id}/renewal-options | 200 các lựa chọn đủ điều kiện | Rental đang hoạt động, thuộc Customer; có policy/eligibility/pricing |
| POST /customer/rentals/{id}/renewal-quote | 200 báo giá bất biến | pricingPackageCode; nguồn có thẩm quyền; không giữ chỗ/kéo dài kỳ thuê |
| POST /customer/rentals/{id}/renewal-requests | 201 Renewal | renewalQuoteId,note; Idempotency-Key; báo giá thuộc Customer còn hạn, đủ điều kiện, chỉ một yêu cầu đang mở |
| PATCH /customer/renewals/{id} | 200 phiên bản điều chỉnh | renewalQuoteId,note,expectedVersion; Idempotency-Key; đang pending |
| POST /customer/renewals/{id}/cancel | 200 đã hủy | reason,expectedVersion; Idempotency-Key; workflow pending đã xác minh; không cần policy |
| GET /{customer,manager}/renewals và /{id} | 200 bản ghi trong phạm vi | Customer có quyền sở hữu; Manager có VIEW_RENTALS + READ |
| POST /manager/renewals/{id}/decision | 200 quyết định | APPROVE/REJECT,reason,expectedVersion; Idempotency-Key; MANAGE_RENTALS + MANAGE |

REJECT cần lý do, không cần policy/tài chính/hold. APPROVE kiểm tra lại Rental đang hoạt động, ngày/phân bổ gian vật lý, giá/policy, nghĩa vụ đến hạn/tranh chấp và giữ chỗ nguyên tử. Duyệt không kéo dài Rental hoặc thay giá cũ/trạng thái gian kho. Tài chính UNKNOWN chặn duyệt, không chặn tạo yêu cầu. Hủy sau khi duyệt/thanh toán và hoàn tất cần phối hợp D3, ngoài phạm vi phần này.

Lệnh từ chối trường body không được hỗ trợ. note/reason tối đa 2000 ký tự; version>=0; key dài 1–100 ký tự. Kiểm tra lại quyền trước khi trả kết quả đã lưu; ràng buộc tài nguyên và mã băm payload chuẩn hóa; trả trạng thái/dữ liệu gốc mà không lặp audit/hold; không lưu correlationId vào kết quả dùng lại. RenewalRequestAdvice giới hạn trong module ánh xạ thiếu header thành 400, không thay bộ xử lý dùng chung hoặc hành vi role khác.

## Truy vấn/dữ liệu tổng hợp

page0,size20 tối đa 100; status đúng enum,rentalId; Manager thêm facilityId/search. Tìm kiếm không phân biệt hoa thường, không diễn giải ký tự đại diện, theo mã gian/tên Customer hoặc UUID Rental/Renewal chính xác. Sắp xếp theo createdAt,newEndDate,amount,id; mặc định createdAt desc + id asc. Tham số không hỗ trợ/lặp trả 400; chi tiết không nhận truy vấn; áp dụng phạm vi/bộ lọc trước phân trang tại cơ sở dữ liệu.

Các trường JSON báo giá được dàn phẳng: id,rentalId,customerId,quotedAt,expiresAt cùng oldEnd, khoảng thời gian nửa mở/newEnd, gian vật lý/cơ sở/loại gian, gói thuê/phiên bản, rate/subtotal/discount/net/deposit/remainder và tham chiếu/phiên bản policy BO. Không có PIN/thông tin gỡ lỗi/bản ghi hợp đồng pháp lý. Chi tiết đọc version workflow/phiên bản đã chấp nhận/người duyệt/thời điểm/lý do/hạn/hold thực tế. Bản ghi cũ không có workflow vẫn là null/UNKNOWN, không giả version0 hoặc lịch sử.

GET tính reviewState và allowedActions theo từng người dùng, không ghi dữ liệu. Tài chính UNKNOWN/null khi nguồn chưa đầy đủ; không bao giờ suy thành nợ bằng 0. Giá/policy thay đổi trước khi duyệt → 409; cần báo giá mới và Customer xác nhận bằng PATCH. Yêu cầu pending không hết hạn chỉ vì TTL của báo giá đã chấp nhận đã hết. Rental không hoạt động hoặc thiếu nguồn không được báo READY.

## Nguồn có thẩm quyền bắt buộc

PolicySource: tham chiếu/phiên bản BO, TTL, cửa sổ thanh toán/gửi yêu cầu, tỷ lệ cọc, ID gói đủ điều kiện. PricingSource: gói/giá/giảm giá/phiên bản có hiệu lực và ánh xạ loại gian. Bộ tính dùng VND với scale2 HALF_UP: bộ kết nối phải công bố rõ moneyScale2; scale0/tiền tệ khác bị chặn, không chuyển đổi. Chủ nguồn phải xác nhận việc công bố quy tắc làm tròn.

### Adapter giá đọc danh mục chung — 09/10/2026

`service/renewal/integration/SharedCatalogRenewalPricingSource` là Spring bean triển khai `RenewalSources.PricingSource`. Chỉ đọc `UnitTypeRepository.findById` và `RentalPackagePolicyRepository.findByFacility_IdOrderByRentalMonthsAsc`, không ghi entity/repository/policy chung và không tạo gói mặc định.

- Giá kỳ mới lấy `UnitType.monthlyPrice` hiện hành của loại gian thực sự gắn với Rental. Không lấy lại applied rate kỳ cũ, không chuyển đổi tiền tệ hoặc nhân 26.000.
- Gói đúng cơ sở, đang active, có hiệu lực tại `extensionStartDate`; cả `effectiveFrom` và `effectiveTo` đều bao gồm ngày biên. Truy vết bằng ID/code/policyVersion đã lưu; kỳ thuê và discount lấy nguyên từ gói.
- VND/scale2 theo hợp đồng tiền hiện hữu trong `ReservationQuoteService` và `RenewalTermCalculator`; adapter không tự công bố chính sách làm tròn mới. Giá sai scale/precision, giảm giá sai giới hạn, metadata mơ hồ hoặc lệch cơ sở trả 409, không sửa dữ liệu để làm hợp lệ.
- Không có gói hợp lệ là danh mục rỗng đã xác minh. Thiếu ánh xạ/loại gian đang dùng không khả dụng trả nguồn không xác minh được. Lỗi DB được truyền lên, không chuyển thành danh sách rỗng hay giá 0.
- Adapter chỉ cung cấp **giá ứng viên**, không quyết định gói được phép dùng cho Renewal. `RenewalWorkflowService.prices` vẫn bắt buộc giao với `PolicySource.eligiblePackageIds`. Không kéo ưu đãi Booking sang Renewal nếu BO chưa cho phép.
- Policy/eligibility/financial consistency/extension hold/approval lifecycle chưa có adapter có thẩm quyền vẫn chặn các lệnh liên quan bằng `DEFERRED_SOURCE`. Có PricingSource không đồng nghĩa options/quote/approval hay toàn bộ D2 đã sẵn sàng.

Không đổi endpoint/DTO/permission, không chạy migration. Test adapter dùng H2 cô lập, không phải bằng chứng E2E MySQL/DB triển khai. Khi owner cung cấp PricingSource thay thế, cần phối hợp chỉ giữ một bean cho port; không thêm source song song hoặc `@Primary` để âm thầm ghi đè nguồn.

EligibilitySource: nguồn gốc ngày đã xác minh/mốc giới hạn Recovery/tính khả thi. FinancialSource: tham chiếu nghĩa vụ đến hạn/tranh chấp với checkedAt; độ mới của dữ liệu đọc có thẩm quyền là trách nhiệm bộ kết nối. ExtensionHoldSource: giao thức dung lượng/khóa dùng chung cho phần kéo dài gian đang chiếm; mặc định participatesInTransaction=false nên chặn duyệt. Lớp triển khai giao dịch local đã được rà soát phải chủ động xác nhận hỗ trợ và bảo đảm rollback cùng bên gọi; không tạo tác động bên ngoài từ xa thiếu tính nguyên tử. Lớp triển khai kiểm thử chỉ có trong dữ liệu H2 của test.

## Lưu trữ/giao dịch

Bổ sung renewal_quotes, renewal_accepted_revisions, renewal_workflows, renewal_open_slots, renewal_idempotency. Giữ nguyên cấu trúc Rental/Renewal/ReservationQuote dùng chung hiện có. Bản chụp/phiên bản điều chỉnh bất biến, ràng buộc duy nhất cho báo giá/phiên bản đã chấp nhận, workflow dùng @Version. Không xóa lịch sử.

Lệnh dùng READ_COMMITTED; thứ tự khóa actor User → Rental → workflow → shared hold; tạo báo giá chỉ khóa Rental. Khóa actor tuần tự hóa xử lý idempotency cùng người dùng; khóa Rental + khóa chính slot bảo đảm một yêu cầu đang mở. Kiểm tra lại quyền sở hữu/phân bổ/phiên bản sau khi khóa. Yêu cầu cũ chưa giải quyết cũng chặn gửi mới. Báo giá đã chấp nhận không được dùng lại sau hủy. Chỉ rejected/cancelled/completed mới giải phóng slot khớp; không tự suy payment_expired là trạng thái kết thúc.

Tính duy nhất của idempotency theo actor/operation/SHA-256(key UTF-8 nguyên văn) tránh nhập nhằng hoa thường/khoảng trắng đệm của collation MySQL. Mã băm chuẩn hóa sắp xếp khóa đối tượng đệ quy, giữ thứ tự mảng; dữ liệu kết quả giữ cách biểu diễn tiền. Không dọn xóa.

Audit tái sử dụng AuditLogService hiện có với người dùng thực trong giao dịch. Hold/hạn/status/audit/idempotency cùng commit; lỗi thì rollback. Khóa local không chứng minh an toàn dung lượng giữa Booking/Return/Assignment; vẫn cần giao thức khóa chung được chủ nguồn thống nhất và kiểm thử hồi quy MySQL.

## Triển khai/lỗi

docs/sql/renewal-persistence-schema.sql là DDL chỉ để rà soát, chưa bật Flyway. Tác vụ này không chạy migration MySQL hoặc thay đổi dữ liệu thực tế. Cấu hình dev ddl-auto=update hiện có có thể tạo bảng khi khởi động: không chạy trên MySQL chung của team trước khi chủ nguồn duyệt. Staging/prod dùng validate cần migration được duyệt; không bổ sung ngược dữ liệu cũ.

400 trường/truy vấn/header sai hoặc không hỗ trợ; 401 phiên đăng nhập không hợp lệ; 403 thiếu quyền; 404 tài nguyên/báo giá ngoài quyền xem; 409 điều khoản/phiên bản cũ, trùng yêu cầu đang mở, hết hạn, xung đột giai đoạn/trả kho/nợ/dung lượng/nguồn. Thiếu nguồn dùng CONFLICT hiện có + thông điệp DEFERRED_SOURCE; không thay enum toàn cục. Triển khai D2 đầy đủ xuyên role cần nguồn thật, duyệt schema/khóa, phối hợp D3 và xác minh MySQL/E2E.
