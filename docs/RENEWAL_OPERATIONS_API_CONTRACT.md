# Đặc tả API vận hành gia hạn — D3

Ngày triển khai: 08/10/2026. Đường dẫn gốc `/api`, JWT Bearer, ApiResponse/PageResponse chuẩn. Đây là phần triển khai BE theo hướng bổ sung, không phải tuyên bố rằng đã sẵn sàng triển khai xuyên role. Đọc cùng RENEWAL_API_CONTRACT.md (D2).

## Điều kiện tích hợp dùng chung

Không cài đặt lớp triển khai chạy thực tế/giá trị mặc định cho các giao diện tích hợp trong `service/renewal/operations/RenewalOperationSources.java`.

| Giao diện tích hợp | Bảo đảm do chủ nguồn cung cấp | Hành vi khi thiếu |
|---|---|---|
| AuthorizationSource | Quyền ký/CASH/ngoại lệ/hoàn tiền thực tế, phân công Staff hiện tại, phạm vi OPERATE/MANAGE, ID được phân công cho hàng đợi trước phân trang | Thao tác Staff/Manager chuyên biệt liên quan trả 409 DEFERRED_SOURCE; không tái sử dụng PERFORM_CHECKIN |
| PolicySource | Cửa sổ ký theo BO, giới hạn kéo dài ngoại lệ, tham chiếu/phiên bản policy, phân loại lỗi cơ sở có thẩm quyền | Không quyết định cọc/ngoại lệ bằng policy viết cứng |
| CalendarSource | Giờ làm việc thực, ngày nghỉ, toàn bộ thời lượng khung hẹn, tính khả thi; giữ/thay khung hẹn trong giao dịch cho mục đích RENEWAL | Không cam kết lịch hẹn; không sửa Check-in |
| SafetySource | Giao thức khóa chung Rental/hold/Booking/Assignment/Return/Recovery/bảo trì; giữ lại/tái giữ chỗ/tiêu thụ/giải phóng hold trong giao dịch | Không cam kết dung lượng hoặc hoàn tất |
| AccountingSource | Liên kết cọc Renewal và các lần thử có thẩm quyền, idempotency ràng buộc tài nguyên, bảng kê phải trả/biên nhận CASH/phân bổ thực, tập khoản thanh toán bắt buộc, đối soát khi hết hạn có thanh toán đang xử lý | Không suy đã thanh toán/nợ bằng 0; không dùng cọc Booking40% thay thế |
| EvidenceSource | Tham chiếu FileAsset thực, kiểm tra người tải lên/liên kết/tài nguyên/mục đích/phạm vi hiển thị | Không nhận URL tùy ý hoặc bằng chứng thuộc tài nguyên khác |
| RefundSource | Quyền được hoàn theo BO, số tiền thực đã trả/đã hoàn/đang được dành để hoàn, SLA theo lịch nghiệp vụ, dành quyền hoàn trong giao dịch | Không báo duyệt/chi trả thành công; duyệt chỉ chờ bên thực thi dùng chung xử lý |

`atomic()` mặc định false. Xác nhận hỗ trợ đòi hỏi giao dịch local/rollback thật và tính nhất quán giữa các bên cùng ghi; chỉ một cờ là chưa đủ. Mọi lỗi nguồn phải rollback thay đổi local và dùng chung. Thực thi thông báo/biên nhận/hoàn tiền thuộc chủ nguồn dùng chung; không tạo URL HTTP không tồn tại hoặc sổ tiền thứ hai.

## Đường dẫn API

| Người thao tác | Phương thức/hậu tố đường dẫn | Body | Kết quả |
|---|---|---|---|
| Customer | GET `/customer/renewals/{id}/operations` | — | Trạng thái vận hành, expectedVersion workflow đã xác minh, missingSources |
| Customer | POST `.../simulated-payment` | expectedVersion | 200 Result; chỉ DEPOSIT, điều khoản/kết quả do server xác định |
| Customer | POST/PATCH `.../appointment` | appointmentAt là thời điểm ISO, reason (bắt buộc với PATCH), expectedVersion | 200 Result; toàn bộ khung hẹn thực nằm trong hạn có hiệu lực |
| Customer | GET `.../appointment` | — | Trạng thái cùng appointmentRef/thời điểm bắt đầu/kết thúc/các hạn hiện tại |
| Customer | POST `.../exception-confirmations` | decisionRef, expectedVersion | 200 Result; chỉ đề xuất mới nhất, kiểm tra lại policy/lỗi/lịch/hold/mốc giới hạn |
| Customer/Manager/Staff | GET `/{role}/renewals/{id}/payments` | page,size | Dòng thời gian sự kiện cọc/CASH D3 đã lưu, không phải lịch sử thanh toán cũ được nhập vào |
| Customer/Manager | GET `/{role}/renewals/{id}/refunds` | page,size | Lịch sử quyết định/dành quyền hoàn, không phải bằng chứng đã chi trả; loại bằng chứng nội bộ khỏi phản hồi Customer |
| Staff | GET `/staff/renewals/{id}` | — | Chỉ trạng thái ký được phân công |
| Staff | GET `/staff/renewal-appointments` | page,size,facilityId,date theo ISO YYYY-MM-DD,status SIGNING/SIGNING_EXPIRED/COMPLETED | Lọc phân công và phạm vi OPERATE TRƯỚC phân trang; sắp xếp appointmentStart/id |
| Staff | POST `/staff/renewals/{id}/arrival` | appointmentRef,evidenceFileIds,expectedVersion | 201 sự kiện theo đồng hồ server; không nhận arrivalAt từ client/ghi lùi thời gian |
| Staff | POST `.../facility-incidents` | appointmentRef,reason,evidenceFileIds,expectedVersion | 201 sự kiện; có thể ghi nhận sự cố sau hết hạn để xem xét |
| Staff | GET `.../payable-statement` | — | statementRef/phiên bản/hạn/số tiền/nghĩa vụ bắt buộc thực tế |
| Staff | POST `.../cash-receipts` | payableStatementRef,receiptReference,received=true,expectedVersion | 201 sự kiện biên nhận thực từ nguồn dùng chung; không phải hoàn tất |
| Staff | POST `.../completion` | expectedVersion,identityVerified=true,arrivalRef,signedDocumentFileId,completionNote | 200 Result; kéo dài Rental đúng một lần |
| Manager | GET `/manager/renewals/{id}/operations` | — | Trạng thái trong phạm vi READ + VIEW_RENTALS |
| Manager | GET `.../facility-incidents` | page,size | Dòng thời gian sự cố/ngoại lệ nội bộ |
| Manager | POST `.../exception-decisions` | incidentId,action,appointmentAt/revisedDeadline khi đổi lịch,reason,evidenceFileIds,expectedVersion | 201 đề xuất; cần quyền chuyên biệt |
| Manager | POST `.../refund-decisions` | incidentId,decision APPROVE/REJECT,reason,evidenceFileIds,expectedVersion | 201 dành quyền hoàn thực tế hoặc từ chối; không bao giờ là chi trả |

Bắt buộc header `Idempotency-Key`, dài 1–100 ký tự. DTO từ chối trường không được hỗ trợ. reason/note tối đa 2000 ký tự, danh sách bằng chứng tối đa 10 ID FileAsset khác nhau và không null. Không nhận amount, outcome, actor, currency, paidAt, overrideDeadline tùy ý hoặc exception=true. Page0,size20 tối đa 100; bộ lọc không hỗ trợ/lặp trả 400; GET không thay đổi dữ liệu.

Result = `{state: RenewalOperationResponse, event: {id,kind,occurredAt,actorId,data}}`; được bao trong ApiResponse. `state.expectedVersion` là CÙNG phiên bản workflow D2 đã xác minh, không phải phiên bản JPA của trạng thái bổ sung. Mỗi thao tác thành công bắt buộc tăng phiên bản workflow. Thử lại phản hồi 201 vẫn trả 201 và kết quả gốc đã lưu. Kiểm tra quyền role/tài nguyên/phạm vi/phân công hiện tại TRƯỚC khi trả kết quả đã lưu; chỉ kiểm tra trạng thái/phiên bản/hạn mới đối với key mới.

## Vòng đời và an toàn

- Điều khoản đã duyệt giữ bất biến. Số tiền cọc lấy từ báo giá Customer đã chấp nhận, không phải danh mục đang chạy hoặc giá trị Manager nhập. Bộ xử lý dùng chung ghi SUCCESS/FAILED/NOT_RECEIVED; lần thử thất bại không ghi đè trạng thái nghiệp vụ đã duyệt/đang ký.
- Trước khi nộp cọc: kiểm tra duyệt/hạn thanh toán/mốc giới hạn, hold và tính khả thi lịch hẹn. Kiểm tra paidAt theo đồng hồ server SAU khi gọi bộ xử lý, số tiền/tiền tệ/liên kết và các hạn. Hạn ký được tính từ thời lượng ký dùng chung và mốc giới hạn Recovery; nộp cọc không kéo dài Rental.
- `renewal_operation_states` riêng theo dõi SIGNING, SIGNING_EXPIRED, PAYMENT_EXPIRED, COMPLETED. Không đổi tên/thêm RenewalStatus dùng chung. Hết hạn ký sau khi đã nộp tiền KHÔNG trở thành payment_expired hoặc tự quy lỗi/mất cọc cho Customer.
- Trạng thái đọc có thể hiển thị SIGNING_EXPIRY_PENDING khi thời gian đã qua nhưng chưa chạy đối soát; GET không thực thi hết hạn. Yêu cầu đã duyệt được xác minh nhưng chưa có dòng D3 hiển thị AWAITING_DEPOSIT; dữ liệu cũ chưa xác định vẫn là UNKNOWN/null.
- Lịch hẹn chỉ thay tham chiếu lịch hiện tại và đặt lại ghi nhận đến hiện tại trước khi khách đến; sau khi khách đến cần xem xét sự cố cơ sở. Calendar giữ khung hẹn thực trong giao dịch, mục đích RENEWAL, không phải Check-in.
- Ghi nhận đến là sự kiện server thực gắn với lịch hiện tại; không thay endDate/hạn. Bằng chứng đã ký và tập nghĩa vụ tài chính đã thanh toán đầy đủ vẫn bắt buộc khi hoàn tất.
- Hoàn tất thông thường khóa actor User → Rental → workflow D2, kiểm tra lại đang hoạt động/chưa trả kho/phân bổ/bản chụp ngày kết thúc, bằng chứng, toàn bộ tập thanh toán bắt buộc và an toàn dùng chung; sau đó tiêu thụ hold, đặt ngày kết thúc Rental đúng newEndDate đã chấp nhận, đánh dấu hoàn tất và chỉ giải phóng renewal open slot khớp. Không giải phóng gian vật lý/quyền truy cập.
- Cho phép hoàn tất đúng thời điểm bằng hạn/mốc giới hạn có hiệu lực; sau đó bị chặn. Cần bảo đảm khóa từ nguồn chung để xử lý cạnh tranh Return/Recovery; chỉ khóa Rental local không chứng minh điều này.
- Ngoại lệ APPROVE_RESCHEDULE_BEFORE_CUTOFF chỉ là đề xuất. Cần lỗi cơ sở đã xác minh, khung hẹn thực và giới hạn kéo dài BO so với hạn GỐC. Theo dõi rõ đề xuất mới nhất; Customer xác nhận thì kiểm tra lại và tái giữ chỗ/tiếp tục giữ hold. Giữ nguyên hạn gốc; yêu cầu kết thúc không được hồi sinh, sau mốc giới hạn không được hoàn tất/tạm dừng Recovery.
- REJECT và REQUEST_POST_CUTOFF_REVIEW chỉ ghi nhận xem xét, không ghi đè hạn hoặc trạng thái Rental. Cần thêm quyền chuyên biệt.
- Duyệt hoàn tiền dành quyền được hoàn thực tế qua chủ nguồn và trả APPROVED_AWAITING_EXECUTION; khoản đang chờ phải ngăn duyệt trùng dưới khóa chung. Không dùng ReturnCase/tiền bảo đảm kho thay thế, khoản thanh toán âm, ví hoặc giao dịch chuyển tiền thực giả lập. Bên thực thi dùng chung phải là Staff được phân công và được cấp quyền, không phải người duyệt, có bằng chứng/lịch sử/idempotency thực; module này KHÔNG tạo API thực thi hoàn tiền.
- `RenewalOperationService.expire(id)` là điểm vào đối soát giao dịch NỘI BỘ. Nó kiểm tra đồng hồ server phải nằm sau hạn, đối soát thanh toán đang xử lý, chỉ giải phóng extension hold và ghi REVIEW_REQUIRED. Giữ lịch sử đã nộp tiền, ngày gốc, open slot và gian vật lý. Không bật bộ lập lịch tự động cho đến khi chủ nguồn/vòng đời/schema duyệt triển khai.

## Lưu trữ/triển khai

Bổ sung bảng renewal_operation_states, renewal_operation_events; sự kiện bất biến mang người thao tác/thời điểm occurredAt từ server thực và liên kết Renewal cha. Tái sử dụng renewal_idempotency của D2 với không gian tên thao tác riêng (`d3_*`); không có bộ nhớ đệm thứ hai. Lệnh D3 không giả phiên bản cho bản ghi cũ.

DDL chỉ để rà soát nằm trong docs/sql/renewal-operations-overdue-schema.sql. Không phải migration Flyway, chưa được thực thi. Cấu hình ddl-auto=update hiện có có thể tạo bảng khi khởi động; KHÔNG chạy trên TiDB/MySQL chung của team trước khi phối hợp duyệt schema. Không bổ sung ngược dữ liệu lịch sử hoặc sửa chữa bản ghi.

400 trường sai định dạng/chèn trường không hợp lệ/thiếu header; 401 phiên đăng nhập không hợp lệ; 403 thiếu role/năng lực được cấp; 404 tài nguyên/tham chiếu lồng ngoài quyền xem; 409 phiên bản/giai đoạn/hạn cũ, xung đột hoặc DEFERRED_SOURCE. Mã lỗi/cấu trúc bao toàn cục hiện có giữ nguyên.

Nghiệm thu production đầy đủ vẫn cần bộ kết nối thật, schema/quyền được chủ nguồn rà soát, đối soát hết hạn/open-slot, khả năng xem kết quả thực thi/đối soát hoàn tiền chung, tích hợp thông báo và E2E MySQL/giữa các bên cùng ghi. Bộ kết nối local trong test chỉ là dữ liệu kiểm thử.
