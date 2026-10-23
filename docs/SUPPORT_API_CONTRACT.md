# D5 — Đặc tả API hỗ trợ

## API trợ lý Gemini thử nghiệm — 23/10/2026

`POST /api/customer/chat/replies` là API trả lời **stateless** riêng, chưa thay thế các API hội thoại/ticket bên dưới. Widget Customer mới gọi endpoint này; các chủ đề FAQ phía FE chỉ là gợi ý câu hỏi, không còn khớp từ khóa để giả phản hồi Gemini. Không tạo SupportTicket, notification, giao dịch hoặc thay đổi hồ sơ. Chưa lưu hội thoại AI vào DB; migration, lịch sử bền vững và bàn giao transcript vẫn là bước sau.

Headers: JWT `Authorization: Bearer …`, `Content-Type: application/json`, `Idempotency-Key: <UUID>`. Chỉ Customer có tài khoản ACTIVE và vai trò CUSTOMER hiện tại trong DB được gọi; không chỉ dựa vào role trên JWT.

```json
{
  "message": "Tôi muốn gia hạn thuê kho thì làm thế nào?",
  "history": [
    { "role": "user", "content": "Chào trợ lý" },
    { "role": "assistant", "content": "Mình có thể giúp bạn tìm hướng dẫn sử dụng StorageHub." }
  ]
}
```

- `message`: bắt buộc, không chỉ khoảng trắng, tối đa 1.000 ký tự. `history`: tùy chọn, tối đa 10 tin, mỗi tin tối đa 1.000 ký tự, role chỉ `user`/`assistant`. Tổng nội dung tối đa 8.000 ký tự; JSON body tối đa 64 KiB. Từ chối field lạ, duplicate JSON field và role system/tool do client truyền.
- History là ngữ cảnh **không đáng tin do client gửi**, không phải hồ sơ đã được server xác thực. Không nhận customerId, facilityId, file, INTERNAL hoặc định danh hồ sơ để tự tra dữ liệu ở bản này. Không gửi bí mật hoặc thông tin cá nhân không cần thiết trong chat.
- BE chèn system prompt và FAQ version `support-faq/2026-10-23-v1` từ resource riêng; không dùng FAQ/roster/demo store hoặc prompt bảo tàng. Chỉ gửi text hướng dẫn và câu hỏi/lịch sử client tới Google. Chưa có lọc/redaction tổng quát cho bí mật do khách tự nhập; không bật cho người dùng thật trước khi hoàn tất review dữ liệu và UI gửi dữ liệu.
- `data` trong envelope `ApiResponse` gồm `requestId`, `authorRole: "AI"`, `source: "FAQ"|"GEMINI"`, `answer`, `model`, `knowledgeVersion`, `generatedAt`, `truncated`. FE hỗ trợ Markdown tối giản (chữ đậm, đoạn, danh sách), không chèn HTML, không tự biến text thành link/image. `truncated=true` nếu provider kết thúc do giới hạn token hoặc text vượt 4.000 ký tự.
- Giới hạn process hiện tại: 10 yêu cầu mới/Customer/phút, tối đa 8 provider call đồng thời; tối đa 2.000 receipt trong cache. Replay cùng Customer/key và payload đã chuẩn hóa trả cùng kết quả hoặc lỗi trong 10 phút tính từ lần tiếp nhận; payload khác trả 409. Request đồng thời cùng key dùng chung kết quả, không gọi provider lần hai. Không tự retry provider. Timeout có thể đã được Google tính sử dụng; giữ cùng key khi kiểm tra lại.
- Receipt chỉ ở RAM, không giữ transcript đầu vào (chỉ hash và kết quả); hết hạn được dọn khi có request tiếp theo. Restart hoặc nhiều instance không chia sẻ receipt/rate limit: chưa bảo đảm idempotency xuyên restart/replica, không coi đây là handoff idempotency bền vững.

| HTTP | Mã lỗi | Ý nghĩa |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Header/body/history không hợp lệ hoặc vượt giới hạn |
| 401 / 403 | `UNAUTHORIZED` / `FORBIDDEN` | Thiếu phiên hợp lệ / không phải Customer active |
| 409 | `CONFLICT` | Key đã dùng với payload khác hoặc request cùng key vẫn đang xử lý |
| 429 | `AI_RATE_LIMITED` | Giới hạn local hoặc quota từ Google |
| 503 | `FEATURE_DISABLED` / `AI_UNAVAILABLE` | Tắt tính năng, thiếu/sai cấu hình hoặc dịch vụ chưa sẵn sàng |
| 504 | `AI_TIMEOUT` | Quá deadline toàn bộ request/response |
| 502 | `AI_PROVIDER_ERROR` | Lỗi provider, phản hồi rỗng/bị chặn/sai cấu trúc; không trả raw error Google |

### Cấu hình backend

Properties có mặc định an toàn trong `SupportAiProperties`; file mẫu `application-example.properties` minh họa binding. Khai báo `storagehub.ai.enabled=false` mặc định, `provider=gemini`, `api-key`, `model`, `base-url=https://generativelanguage.googleapis.com/v1beta/openai`, `timeout-ms=15000` (1.000–30.000), `max-output-tokens=768` (128–2.048), `temperature=0.2` (0–1). Không có model fallback tự động. Key không có trong FE, log hoặc cấu hình được commit. Endpoint chỉ chấp nhận host/đường dẫn Google cố định, không follow redirect.

`application.properties` import tùy chọn `application-local.properties` và `.local/gemini.local.properties` (đường dẫn tương đối theo working directory BE). File Gemini trên máy phát triển đã được đặt `enabled=true` theo lượt nối widget; file này được Git-ignore, key không nằm trong source. Khi không có file hoặc biến bật, mặc định vẫn tắt. Biến `STORAGEHUB_AI_ENABLED=false` có thể ghi đè để tắt. Cần khởi động lại backend để nạp code/cấu hình mới. Chưa xác nhận key/model hoạt động, chưa gọi Google trong lượt triển khai này.

### Hành vi widget Customer đã nối API

- FE tạo UUID v4 cho từng câu hỏi, gửi bằng API client dùng JWT hiện tại, kiểm tra response envelope/requestId/authorRole/text/timestamp trước khi hiển thị. Không đưa key Gemini vào bundle.
- Hiển thị câu hỏi ngay, trạng thái đang trả lời và lỗi an toàn. Không có nút Kiểm tra lại/Gửi câu hỏi khác; khách dùng ô nhập và tab Nhân viên. Sau lỗi ô gửi được mở lại, không tự retry. Nếu khách tự gửi lại đúng câu hỏi có kết quả chưa xác định do mạng/response không hợp lệ, FE giữ cùng key/message/history trong 9 phút, không thêm bubble trùng. Với lỗi server đã xác định (như provider quá tải), lần gửi chủ động tiếp theo dùng key mới. Câu hỏi lỗi không đưa vào history.
- History chỉ gồm trao đổi thành công, bỏ lời chào UI, tối đa 10 tin và 8.000 ký tự tổng; mỗi nội dung history được giới hạn 1.000 ký tự. Answer được render thành text, không chèn HTML. Nếu `truncated`, hiển thị thông báo đạt giới hạn độ dài.
- Thu nhỏ/đổi trang giữ component và draft trong phiên; đổi tài khoản hủy state, response về muộn không được áp dụng cho tài khoản mới. Chưa có persistence sau reload hoặc transcript bàn giao. Chọn kênh nhân viên không tự tạo ticket.
- Tooltip thông báo câu phổ biến dùng hướng dẫn có sẵn, câu khác và lịch sử gần đây gửi Gemini; nhắc không nhập mật khẩu, PIN/OTP hoặc thông tin thẻ. FAQ được ghi rõ “Hướng dẫn nhanh”, không giả phản hồi Gemini.

### FAQ trả nhanh và cách trình bày

Backend `SupportQuickFaq` chứa 10 câu hỏi hướng dẫn chung đã duyệt. Chỉ khớp toàn bộ câu sau chuẩn hóa dấu, khoảng trắng, chữ hoa/thường và dấu câu; không dùng từ khóa rộng để suy đoán câu hỏi về hồ sơ. Những câu gợi ý Chọn kho/Gia hạn/Thanh toán/Trả kho gửi đúng câu chuẩn và nhận kết quả FAQ không gọi Google. Lịch sử vẫn được validate/fingerprint; quyền Customer, rate limit và receipt replay áp dụng như trước. FAQ không cần slot gọi Gemini và vẫn hoạt động khi provider chưa bật/cấu hình; đây là hướng dẫn tĩnh, không chứng nhận dữ liệu hồ sơ hoặc gọi công cụ. `source=FAQ`, `model=faq-v1`; câu khác có `source=GEMINI` và giữ kiểm tra provider.

Prompt Gemini yêu cầu 2–4 câu ngắn khoảng 40–90 từ cho hướng dẫn thông thường; tối đa 3 bước khi cần, không chào lại hoặc thêm lời mời hỗ trợ chung. Giới hạn token vẫn là trần an toàn, không ép cắt đáp án giữa câu để rút ngắn. Log kết quả thành công chỉ ghi `source` và `elapsedMs`, không có chat/key/Customer ID. Chưa triển khai streaming; FAQ tránh lượt gọi provider, nhưng câu hỏi Gemini vẫn phụ thuộc độ trễ mạng/model.

Cập nhật kết nối local 23/10/2026: model `gemini-3.5-flash` trả 503 `UNAVAILABLE` do quá tải trong lần chẩn đoán trực tiếp. Đã chuyển file cấu hình local sang `gemini-3.5-flash-lite`, temperature `0.2`, timeout `25000ms`; adapter Java nhận text thực tế trong khoảng 4,5 giây. Không thay model tự động trong runtime và không gửi lại tự động. Lỗi Google 503 được map `AI_UNAVAILABLE`; log chỉ ghi HTTP status/model hoặc loại lỗi transport, không có raw body/key/chat.

Adapter dùng Java HttpClient, deadline toàn bộ body, tối đa 64 KiB response, không giữ transaction DB khi gọi model. Không thêm Spring AI/PGVector. Endpoint/schema tham chiếu [tài liệu tương thích OpenAI chính thức của Gemini](https://ai.google.dev/gemini-api/docs/openai); đây là giao thức gọi Gemini, không phải chuyển provider sang OpenAI.

## Giao diện hội thoại — 23/10/2026

- FE hiển thị danh sách hội thoại và khung chat trực tiếp cho Customer/Staff/Manager, thay bảng yêu cầu và cửa sổ gửi đơn.
- Customer chọn cơ sở hoặc hồ sơ liên quan rồi gửi tin nhắn đầu tiên; không nhập tiêu đề riêng. FE dùng bản rút gọn tin đầu làm `subject`, toàn bộ tin làm `description` khi tạo workflow bằng API hiện có.
- Tạo thành công mở ngay hội thoại được server trả về. Tin nhắn và danh sách tự cập nhật khi trang hiển thị; tạm ngừng cập nhật khi đang gửi hoặc tải tệp. Đây là polling, không phải WebSocket.
- Staff nhận hội thoại từ hàng chờ thuộc cơ sở được phân công. Giữ quyền, scope, phiên bản, idempotency, tệp PUBLIC/INTERNAL và xác nhận kết quả hiện có; không tự chuyển hội thoại sang cơ sở khác hoặc tự nhận thay Staff.
- Dữ liệu vẫn dùng SupportTicket/SupportState hiện có để bảo toàn lịch sử; không thay schema hoặc contract payload. Các tên ticket/yêu cầu bên dưới là khái niệm lưu trữ/nghiệp vụ nội bộ.

Nghiệp vụ hiện hành cập nhật 15/10/2026: Customer trao đổi trực tiếp với Staff; Staff tự nhận yêu cầu. Manager theo dõi trong phạm vi cơ sở và trao đổi riêng với Staff qua tin nhắn INTERNAL.

## Báo kết quả xử lý hỗ trợ

Thông báo hỗ trợ dùng tiêu đề hệ thống tiếng Việt. API danh sách/đánh dấu đã đọc cũng dịch các tiêu đề tiếng Anh đã lưu từ phiên bản cũ, không sửa lịch sử DB. Nội dung do người dùng nhập được dùng làm tên yêu cầu tham chiếu.

| Sự kiện | Người nhận thông báo |
| --- | --- |
| Tạo yêu cầu | Khách hàng, nhân viên đủ quyền tại cơ sở, quản lý cơ sở |
| Nhận xử lý | Khách hàng và quản lý cơ sở |
| Khách gửi tin nhắn | Nhân viên phụ trách còn đủ quyền và quản lý cơ sở |
| Nhân viên trả lời khách | Khách hàng và quản lý cơ sở |
| Nhân viên trao đổi riêng | Quản lý cơ sở |
| Quản lý trao đổi riêng | Nhân viên đang phụ trách |
| Báo xử lý xong | Khách hàng (qua thông báo xác nhận kết quả) và quản lý cơ sở |
| Khách xác nhận đóng | Khách hàng, nhân viên phụ trách còn đủ quyền và quản lý cơ sở |
| Khách yêu cầu xử lý lại | Khách hàng, quản lý và nhân viên phụ trách; nếu phân công cũ không còn hợp lệ thì báo nhân viên đủ quyền trong hàng chờ |
| Tự đóng theo chính sách | Khách hàng, nhân viên phụ trách còn đủ quyền và quản lý cơ sở |

Thông báo giữ ID yêu cầu liên quan và hiển thị tại trang hỗ trợ tương ứng của từng vai trò, không đưa nội dung trao đổi riêng vào thông báo của khách hàng. Thử lại cùng khóa không tạo thông báo trùng.

Ràng buộc: phải có mô tả không chỉ gồm khoảng trắng hoặc ít nhất một tệp minh chứng PUBLIC hợp lệ đã lưu. `summary` tối đa 4000 ký tự; nếu chỉ gửi tệp, hệ thống thêm lời nhắn yêu cầu khách kiểm tra minh chứng và xác nhận. Không có cả hai trả 400, không đổi trạng thái hoặc gửi thông báo. File chưa lưu, file INTERNAL hoặc không thuộc hồ sơ/người tải bị từ chối theo kiểm tra minh chứng hiện có. Ghi tin nhắn PUBLIC, đính kèm, đổi trạng thái và tạo thông báo thuộc cùng transaction.

`POST S/{id}/resolution` ghi nhận nội dung và minh chứng của Staff, chuyển yêu cầu từ `in_progress` sang `resolved` (đã xử lý, chờ khách xác nhận). Hồ sơ thuê/thanh toán liên kết cung cấp ngữ cảnh; không bắt buộc bộ xác minh kết quả module chỉ vì có liên kết. Thao tác này không xác nhận thu/hoàn tiền hoặc hoàn tất hồ sơ liên kết. Customer xác nhận đóng theo chính sách hoặc yêu cầu mở lại. Phạm vi, phân công, phiên bản, minh chứng và kết quả escalation lịch sử vẫn được kiểm tra. Quy tắc này áp dụng cả khi đóng thủ công hoặc tự đóng yêu cầu hỗ trợ.

## Trao đổi Staff–Manager

- Staff/Manager: GET `/{id}/public-messages` và `/{id}/internal-messages` dưới tiền tố D5 tương ứng. Chỉ nhận page/size; lọc PUBLIC/INTERNAL trước đếm và phân trang. GET `/messages` cũ vẫn giữ tương thích. Customer chỉ có tin PUBLIC.
- Manager: POST `/api/manager/support-tickets/{id}/messages`, header `Idempotency-Key`, body `{"body":"Hướng dẫn xử lý","visibility":"INTERNAL","evidenceFileIds":[]}`. PUBLIC bị từ chối; không nhận expectedVersion, actor hoặc người nhận do client chọn.
- Manager cần role đang hoạt động, `support:read` và phạm vi MANAGE tại cơ sở (kiểm tra token và DB). Hồ sơ phải còn hiệu lực xử lý và có Staff đang nhận. Gửi tin không đổi trạng thái hoặc đồng hồ phản hồi Customer; chỉ thông báo Staff đang phụ trách. Retry cùng key không gửi trùng.
- Staff gửi INTERNAL qua endpoint messages hiện có; thông báo Manager của cơ sở. Hai bên đính kèm `DUONG_SUPPORT_INTERNAL`; Customer không được đọc hoặc tải các tệp này. Manager chỉ upload/attach chứng từ INTERNAL.
- FE bỏ tab lịch sử nội bộ, thay điều phối bằng trao đổi Staff–Manager; giữ giao diện và composer tương tự cuộc trao đổi với Customer. API lịch sử và escalation vẫn tồn tại để đọc dữ liệu cũ.

Các mô tả Manager chỉ đọc bên dưới phản ánh quy tắc trước cập nhật này; ngoại lệ hiện hành duy nhất là gửi tin nhắn và đính kèm INTERNAL như trên. Manager vẫn không có quyền phân công, quyết định chuyển bộ phận hoặc đổi trạng thái hồ sơ.

## Trách nhiệm và ranh giới

- Customer phụ trách tạo yêu cầu/trao đổi công khai, chủ động mở lại và xác nhận kết quả xử lý.
- Facility Manager chỉ đọc danh sách, tổng hợp, chi tiết và lịch sử trong phạm vi cơ sở; không phân công, giao lại, nhắn tin, quyết định chuyển tiếp hay thay đổi trạng thái.
- Facility Staff đang hoạt động tự nhận yêu cầu chưa có người xử lý qua claim, rồi trao đổi, yêu cầu bổ sung và báo kết quả. Khóa ticket bảo đảm một Staff nhận thành công; Staff khác không truy cập nội dung sau khi ticket được nhận.
- Business Operations sở hữu policy SLA/đóng yêu cầu và lịch làm việc. Support áp dụng policy; không tạo policy riêng hoặc sửa dữ liệu BO.
- Chủ các module dùng chung sở hữu kết quả thanh toán/hoàn tiền/trả kho/bảo trì/tài khoản/gia hạn/quá hạn. Tin nhắn Support không phải bằng chứng các thao tác đó đã thành công.
- Tái sử dụng nguyên trạng `SupportTicket`, `SupportTicketStatus`, `User`, quyền, phạm vi cơ sở và dịch vụ file hiện có. Không có nguồn phân công nhiệm vụ song song, backend mới, API bên ngoài hoặc bản ghi nghiệp vụ tạo sẵn.

## Xác thực và phạm vi

Mọi đường dẫn sử dụng xác thực Bearer và cấu trúc bao phản hồi hiện có (`ApiResponse`, `PageResponse`, `ApiErrorResponse`, correlation ID).

Kiểm tra trạng thái/role/quyền/phạm vi cơ sở hiện trong DB và token. Customer phải sở hữu ticket và hồ sơ liên kết. Manager đọc chỉ cần `support:read` + READ; không có API mutation. Principal có role MANAGER không được dùng role STAFF đồng thời để thực hiện lệnh Support. Staff cần `support:read` + `support:update` và OPERATE/MANAGE tại cơ sở để xem hàng đợi an toàn và claim; nội dung/trao đổi sau claim chỉ thuộc Staff hiện đang xử lý.

`RoleDataInitializer` hiện có `support:read` và `support:update` trong bộ quyền STAFF mặc định. Manager chỉ có `support:read`; không còn kế thừa Staff và không được thao tác Support. Quyền đã bị thu hồi/tùy chỉnh và phạm vi cơ sở vẫn được kiểm tra thực tế.

Ticket cũ không có cơ sở bị loại khỏi danh sách theo phạm vi và trả 404; không ngầm có quyền phân loại/xử lý toàn hệ thống. Ticket cũ có cơ sở trong phạm vi nhưng chưa có metadata workflow đã xác minh được đọc với `workflowReady=false`, không được thay đổi. Không tự bổ sung ngược dữ liệu.

## Đường dẫn API

Tiền tố D5 `C = /api/customer/support-workflows`, `M = /api/manager/support-tickets`, `S = /api/staff/support-workflows`.

### Tương thích sau tích hợp develop (09/10/2026)

- `develop` đã loại bỏ hai controller cũ tại `/api/customer/support-tickets` và `/api/staff/support-tickets`. Các đường dẫn này không còn được đăng ký; D5 không khôi phục controller cũ hoặc thêm handler thay thế tại đó. Swagger và kiểm thử mapping xác nhận điều này.
- Hai controller D5 Customer/Staff dùng tiền tố `support-workflows` để giữ đầy đủ luồng có phiên bản, idempotency, phân công và giới hạn hiển thị riêng. API Manager không đổi. Client D5 phải dùng đúng tiền tố mới; không fallback sang API dùng chung vì DTO và quy tắc lệnh khác nhau.
- D5 vẫn tham chiếu `SupportTicket` hiện có và chỉ thay đổi ticket có metadata workflow đã xác minh. Việc bỏ controller cũ không di chuyển dữ liệu hoặc tự bổ sung metadata cho ticket cũ, cũng không đồng bộ lịch sử giữa `support_ticket_messages` và `support_messages`.
- Client thuộc module khác còn gọi đường dẫn cũ cần owner cập nhật theo contract D5. Nếu cần chuyển lịch sử/trạng thái cũ, team phải thống nhất riêng; không dùng việc xóa controller hay đổi URL như bằng chứng dữ liệu đã được hợp nhất.

| Phương thức / đường dẫn | Hành vi |
| --- | --- |
| POST C | Tạo ticket thuộc Customer; 201 |
| GET C, GET C/{id} | Danh sách/chi tiết thuộc Customer |
| GET C/{id}/messages | Chỉ tin nhắn PUBLIC; loại dòng nội bộ trước đếm/phân trang |
| POST C/{id}/messages | Phản hồi công khai; 201 |
| POST C/{id}/close | Customer xác nhận ticket đã xử lý; cần policy chung |
| POST C/{id}/reopen | Chủ động chuyển resolved → in_progress/open |
| POST C/{id}/follow-ups | Ticket mới liên kết ticket cha đã đóng; 201 |
| GET M, GET M/{id} | Danh sách/chi tiết trong phạm vi |
| GET M/summary?facilityId=UUID | Tổng hợp trạng thái trong phạm vi READ |
| GET M/{id}/messages, /events, /escalations | Dữ liệu nội bộ/công khai trong phạm vi |
| GET S, GET S/{id} | Danh sách/chi tiết hiện được phân công |
| GET S/queue | Hàng đợi chưa được nhận: chỉ metadata an toàn, lọc phạm vi trước phân trang |
| POST S/{id}/claim | Staff tự nhận và bắt đầu xử lý trong cùng transaction |
| POST S/{id}/accept | open → in_progress |
| GET S/{id}/messages, /events, /escalations | Dữ liệu thuộc phân công hiện tại |
| POST S/{id}/messages | Phản hồi PUBLIC hoặc ghi chú INTERNAL; 201 |
| POST S/{id}/request-information | in_progress → waiting_customer |
| POST S/{id}/resolution | in_progress → resolved, kết quả đã xác minh |

Không có endpoint Manager báo xử lý xong, dòng thời gian nội bộ cho Customer, endpoint chọn người nhận tùy ý, endpoint client ghi ACK/kết quả, hoặc endpoint công khai tự đóng.

## Quy tắc payload và thử lại

Mọi lệnh POST yêu cầu `Idempotency-Key` (1–100 ký tự, không chỉ chứa khoảng trắng). Phạm vi: người thao tác + thao tác lệnh + key; tạo dấu nhận diện từ ID tài nguyên và payload yêu cầu đã chuẩn hóa. Thử lại giống hệt trả kết quả đã lưu, không thay đổi dữ liệu/gửi thông báo lần hai. Cùng key nhưng payload khác trả 409. Kiểm tra lại quyền trước khi trả kết quả đã lưu; trả kết quả đã lưu trước khi kiểm tra trạng thái/phiên bản mới. Key được băm trong bản ghi xác nhận lệnh. Giữ các bản ghi xác nhận chừng nào còn cần bảo đảm thử lại; không âm thầm cho hết hạn.

Chuyển trạng thái/phân công/điều phối cần `expectedVersion` không âm lấy từ chi tiết ticket mới nhất. Phản hồi Customer bắt buộc trường này khi tiếp tục từ `waiting_customer`. Thêm tin nhắn thông thường không cần phiên bản dự kiến. Khóa bi quan trên ticket tuần tự hóa thêm tin/chuyển trạng thái/giao lại/tự đóng; `@Version` trên workflow riêng phát hiện chuyển trạng thái theo phiên bản cũ, không thêm cột version vào entity dùng chung. Phiên bản phân công tăng khi giao lại và việc đã nhận bị đặt lại.

Tham số truy vấn không hỗ trợ/lặp, thuộc tính JSON không hỗ trợ, khóa JSON trùng, JSON thừa phía sau và body trên 64 KiB chỉ bị từ chối trong controller D5. Không thay cấu hình Jackson của module hiện có.

Giới hạn kỹ thuật truyền dữ liệu (không phải policy BO): subject 1–200, description/message/summary 1–4000, reason 1–2000, feedback tùy chọn tối đa 2000 ký tự; tối đa 10 UUID file khác nhau và không null. Không nhận URL nguyên văn. Customer không được gửi priority, visibility, actor/assignee tùy ý hoặc trạng thái kết quả.

Ví dụ tạo không liên kết hồ sơ:

```json
{"subject":"Access question","description":"Please help","facilityId":"<active-facility-uuid>","evidenceFileIds":[]}
```

Ví dụ liên kết hồ sơ thuộc Customer (cơ sở được suy ra; nếu gửi thêm cơ sở thì phải khớp):

```json
{"subject":"Rental question","description":"Please check this record","linkedRecord":{"type":"RENTAL","id":"<owned-rental-uuid>"}}
```

Loại liên kết: `RENTAL`, `RESERVATION`, `PAYMENT`, `STORAGE_UNIT`. Quyền sở hữu Payment theo Customer của Reservation tương ứng, không chỉ theo người khởi tạo thanh toán. Liên kết gian kho yêu cầu quan hệ Rental với Customer, không chỉ biết UUID gian kho. Khi không liên kết hồ sơ, cần ID cơ sở đang hoạt động.

Tự nhận: `POST S/{id}/claim`, body `{"expectedVersion":0}`, header `Idempotency-Key`. Phiên bản lấy từ queue hiện tại; claim ghi người xử lý, sự kiện ASSIGNED/ACCEPTED và chuyển open → in_progress trong cùng transaction. Retry đúng key/payload đọc lại kết quả; Staff cạnh tranh hoặc stale version trả 409.

Nhận việc: `{"expectedVersion":1}`. Tin nhắn Staff: `{"body":"Progress update","visibility":"PUBLIC","evidenceFileIds":[]}`. Tin nhắn Customer: `{"body":"Requested details","expectedVersion":2}`. Yêu cầu bổ sung: `{"message":"Please provide details","expectedVersion":2}`. Báo kết quả: `{"summary":"Outcome and explanation","expectedVersion":3}`. Mở lại: `{"reason":"Issue persists","expectedVersion":4}`. Đóng: `{"expectedVersion":4,"feedback":"Confirmed"}`.

## Vòng đời

- Ticket mới: `open`, chưa có người xử lý, nằm trong hàng đợi Staff theo cơ sở. Manager đọc để theo dõi.
- Staff claim ghi người xử lý và chấp nhận việc ngay; không có bước Manager phân công/giao lại. `/accept` chỉ giữ cho hồ sơ lịch sử đã được phân công nhưng chưa nhận.
- Staff phải là người đã nhận hiện tại trước khi nhắn/xử lý/báo kết quả.
- `request-information` tạo câu hỏi PUBLIC và chuyển `waiting_customer`; ghi chú INTERNAL không đổi trạng thái hoặc thông báo Customer. Phản hồi PUBLIC của Customer kèm phiên bản tiếp tục `in_progress`.
- Phản hồi PUBLIC của Staff thông báo Customer và ghi thời điểm phản hồi công khai đầu/cuối. Ghi chú nội bộ không thực hiện hai việc này.
- Báo kết quả cần Staff hiện tại đã nhận, trạng thái `in_progress`, nội dung kết quả và không còn escalation chưa giải quyết. Hồ sơ liên kết là ngữ cảnh hỗ trợ; báo kết quả không thay đổi hoặc xác nhận hoàn tất nghiệp vụ của hồ sơ đó.
- Trả lời ticket `resolved` không âm thầm mở lại. Chủ động mở lại trả cho Staff đủ điều kiện đã nhận (`in_progress`), hoặc xóa người xử lý không còn đủ điều kiện và trả về hàng đợi Staff để claim (`open`).
- Ticket `closed` không thể được trả lời/mở lại/giao lại. Customer tạo yêu cầu tiếp nối liên kết cùng cơ sở; không sao chép nội dung hoặc file nội bộ.
- Đóng ticket không xóa nợ, đánh dấu hoàn tiền đã chi, giải phóng gian kho, đóng hồ sơ Return hoặc hoàn thành nhiệm vụ của module khác.

## Nguồn chung / tích hợp theo nguyên tắc thiếu điều kiện thì chặn

Các giao diện mở rộng nằm trong `SupportSources`; **không cài đặt lớp triển khai giả chạy thực tế**. Bộ kết nối kiểm thử chỉ nằm trong `src/test`.

| Nguồn dùng chung | Tích hợp / hành vi khi thiếu |
| --- | --- |
| EvidenceSource | Phải triển khai quyền đính kèm/đọc hiện tại và ràng buộc nguyên tử quyền sở hữu/phạm vi hiển thị file trong cùng giao dịch hoặc qua outbox bền vững. Thiếu nguồn/nguồn không nguyên tử: lệnh có file đính kèm trả 409 `DEFERRED_SOURCE`; lệnh chỉ văn bản vẫn dùng được. Thiếu nguồn đọc: trả UNKNOWN, không trả ID file. Tiếp tục dùng quyền tải file chung, không tạo endpoint mới. |
| SlaSource | Priority/policy/phiên bản BO + hạn phản hồi đầu/cập nhật hằng ngày tiếp theo tính theo lịch. Thiếu: `slaCompleteness=UNKNOWN`, SLA null, lý do thiếu rõ ràng. Không suy số 0/đang đúng hạn hoặc cho Customer đặt priority. Tạm dừng xử lý chỉ được phản ánh `waiting_customer`. |
| ClosePolicySource | Policy/phiên bản BO, quyền Customer đóng và hạn tự đóng có thẩm quyền. Thiếu: chặn đóng/tự đóng. Hạn không được trước thời điểm resolved + 7 ngày lịch. |
| NotificationSource | Bằng chứng delivery của thông báo kết quả/thời gian xem xét, gắn đúng ticket, Customer, RESOLVED event hiện tại và policy/version. `consistentThroughClose()` mặc định false; chưa có authoritative adapter thì auto-close bị chặn. Không dùng notification queued/persisted làm delivery proof. |
| ResolutionSource | Điểm mở rộng cũ, không còn là điều kiện báo kết quả/đóng yêu cầu chỉ vì có hồ sơ liên kết. Kết quả Support được khách xem xét; kết quả nghiệp vụ liên kết vẫn thuộc module tương ứng. |
| EscalationSource | Chỉ đọc/xác minh kết quả điều phối lịch sử. Thiếu nguồn thì kết quả cũ vẫn UNKNOWN và chặn resolve/close; không tạo chuyển tiếp mới. |

Mục tiêu SLA đã duyệt: HIGH 1 / MEDIUM 4 / LOW 8 giờ làm việc để phản hồi đầu tiên; yêu cầu chưa giải quyết phải cập nhật mỗi ngày làm việc. `waiting_customer` tạm dừng xử lý chủ động, không xóa nghĩa vụ phản hồi đầu/cập nhật đã phát sinh. Chờ nội bộ không phải chờ Customer. Bộ kết nối phụ trách tính lịch và hạch toán lịch sử tạm dừng; cấm thay bằng cách tính thời gian thực trôi qua không xét lịch. Phần lõi lưu thời điểm phân công/phản hồi và lịch sử sự kiện để tích hợp. Chế độ thiếu nguồn **không** khẳng định giám sát/cảnh báo SLA đang hoạt động.

`SupportService.autoClose(id)` chỉ dành cho hệ thống: khóa và kiểm tra lại trạng thái đã xử lý, hạn xem xét tối thiểu 7 ngày, kết quả escalation lịch sử đáng tin cậy và delivery proof từ `NotificationSource`, rồi ghi thông báo đóng + đóng + audit trong một giao dịch. Proof cần reference thật, đúng ticket/recipient/RESOLVED event của lần xử lý hiện tại và policy/version; deliveredAt phải từ lúc resolved tới serverNow. Không tìm được duy nhất event hiện tại hoặc thiếu nguồn/proof trả 409 DEFERRED_SOURCE; proof không khớp trả 409. Bằng chứng từ lần resolved trước không dùng lại sau reopen/resolve. Owner phải chứng minh consistency tới commit; không bật cờ bằng adapter giả. Không có endpoint nhận proof từ client.

Thông báo được tạo trong giao dịch resolve/auto-close vẫn dùng `NotificationService` hiện có; việc ghi bản ghi không chứng minh email/delivery thành công. Tác vụ không sửa service này, không gửi email thật và không tự bật job. Customer chủ động đóng theo policy là nhánh khác, không bị yêu cầu auto-close delivery proof. FE không suy ngày tự đóng từ resolvedAt hoặc SLA và không hứa tự đóng khi chưa có nguồn.

`SupportAutoCloseCoordinator` chỉ được bật chủ động (`app.support.auto-close.enabled=true`; không có/false thì không tạo bean/job). Thành phần quét metadata đã xử lý được xác minh theo từng lô có giới hạn bằng phân trang theo khóa, với giao dịch khóa độc lập cho từng ticket; thiếu nguồn không đồng nghĩa đóng thành công và không làm các ticket phía sau bị bỏ đói. Cấu hình: batch-size mặc định 100, cho phép 1..500; initial-delay-ms/fixed-delay-ms mặc định 60000. Không bật hoặc thêm thuộc tính nào vào cấu hình chung. Chủ policy/lịch/schema phải cung cấp nguồn và duyệt kích hoạt/giới hạn quét trước khi dùng. Cạnh tranh giữa nhiều instance được xử lý bằng khóa ticket và kiểm tra lại trạng thái resolved, không giả định chỉ có một instance lập lịch.

## Lịch sử điều phối và các API đã gỡ

Nghiệp vụ mới chỉ có tương tác Customer/Staff. Đã gỡ POST Manager assignment, POST Manager escalation decision, GET Manager staff-options và POST Staff escalations. Manager chỉ có GET; các lệnh nội bộ cũ cũng từ chối trước khi đọc receipt/mutation, nên idempotency cũ không hồi sinh thao tác đã bị gỡ.

GET messages/events/escalations vẫn giữ lịch sử có phạm vi. Manager được đọc file theo quyền và upload/attach bằng chứng INTERNAL theo quy tắc trao đổi mới. Không xóa sự kiện, người xử lý hoặc receiver reference cũ. Các escalation lịch sử chưa có kết quả thật tiếp tục chặn resolve/close; không tự đánh dấu REJECTED/COMPLETED để bỏ qua. Liên kết Payment/Return/Renewal chỉ cung cấp ngữ cảnh; yêu cầu hỗ trợ không tự thay đổi trạng thái hoặc kết quả của module liên kết. Yêu cầu cũ đã có Staff nhận tiếp tục do người đó xử lý.

UI chưa được sửa trong tác vụ này. Client gọi API mutation đã gỡ cần được cập nhật ở một thay đổi giao diện riêng; contract BE không fallback sang thao tác Manager cũ.

## Ý nghĩa truy vấn / phản hồi

Truy vấn danh sách: page (bắt đầu từ 0, mặc định 0), size (mặc định 20, tối đa 100), status (enum chữ thường hiện có), search (subject/description, bỏ khoảng trắng đầu/cuối, tối đa 200 ký tự, xử lý ký tự đại diện thành ký tự tìm kiếm nguyên văn), sort (`createdAt|updatedAt|id|subject,asc|desc`, mặc định createdAt,desc). Manager thêm facilityId/staffId. Áp dụng phạm vi/quyền sở hữu/bộ lọc trước đếm và phân trang; ID dùng phân định ổn định khi bằng giá trị. Đường dẫn dòng thời gian chỉ nhận page/size và sắp xếp theo thời điểm rồi ID. Chi tiết không có tham số truy vấn. Không công bố bộ lọc priority/SLA khi chưa có dữ liệu có thẩm quyền.

Chi tiết ticket trả các ID, trạng thái nghiệp vụ, subject/description, thời điểm phân công/nhận/xử lý xong/đóng, version, assignmentRevision, tham chiếu tiếp nối/liên kết, workflowReady và mức đầy đủ SLA rõ ràng. Không tuần tự hóa entity hoặc thông tin truy cập nguyên văn. Sự kiện/escalation nội bộ là endpoint riêng chỉ Staff/Manager được dùng. ID file tin nhắn cần quyền đọc.

Lỗi: 400 đầu vào/truy vấn/header không hợp lệ; 401 chưa xác thực; 403 thiếu role/quyền/phạm vi; 404 đối tượng không có sẵn/thuộc người khác; 409 phiên bản cũ, vòng đời không cho phép, key xung đột, dữ liệu có thẩm quyền không nhất quán hoặc `DEFERRED_SOURCE`. Chỉ thử lại 409 sau khi hiểu lý do; không xem thiếu nguồn là thành công tạm thời.

## Lưu trữ / triển khai

Bổ sung năm bảng: support_workflow_states, support_messages, support_workflow_events, support_escalations, support_command_receipts. Không ALTER bảng chung, xóa/viết lại bản ghi, tự chuyển đổi dữ liệu cũ hoặc tạo sẵn policy. DDL chỉ để rà soát: `docs/sql/support-workflow-schema.sql`.

Ứng dụng hiện dùng Hibernate `ddl-auto=update`, có thể tạo các bảng này khi khởi động. Không chạy bản build này trên DB chung của team trước khi chủ schema và phân quyền rà soát. Test H2 tạo schema riêng có thể xóa sau kiểm thử; đây không phải xác minh migration MySQL/TiDB.

Swagger: `/swagger-ui/index.html`, `/v3/api-docs` hiện có; các thẻ `D5 - Customer Support`, `D5 - Manager Support`, `D5 - Assigned Staff Support`. Dùng token role thật/quyền sở hữu dữ liệu kiểm thử đúng và phiên bản mới nhất; chặn thiếu nguồn bằng 409 là hành vi dự kiến, không phải hướng dẫn vượt chặn.
