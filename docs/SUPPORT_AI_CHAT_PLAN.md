# Kế hoạch hỗ trợ khách hàng bằng AI và bàn giao Staff

Ngày lập: 23/10/2026. Đây là kế hoạch triển khai, chưa phải xác nhận tính năng đã hoàn tất. Phần chat trực tiếp với Staff đã có thay đổi đang phát triển; lớp AI và cơ chế AI-first dưới đây là công việc tiếp theo.

## 1. Mục tiêu và phạm vi

- Bỏ mục Hỗ trợ khách hàng khỏi thanh điều hướng **Customer**, thay bằng một icon chat nổi dùng chung trong khu vực khách hàng.
- Khách mở chat và được AI hỗ trợ trước; chỉ tạo yêu cầu cho Staff khi cần bàn giao.
- Staff/Manager giữ màn hình công việc để tiếp nhận, trao đổi và theo dõi hội thoại theo quyền hiện có.
- Gia hạn, quá hạn, đặt kho, trả kho và thanh toán là nguồn thông tin/ngữ cảnh; chưa thay đổi quy tắc nghiệp vụ của các module đó.
- Bản đầu ưu tiên AI hướng dẫn/tra cứu; chưa cho AI tự thanh toán, gia hạn, hủy đơn, hoàn tiền hoặc thay đổi quyền truy cập.

## 2. Luồng mục tiêu

```text
Customer mở icon chat → gửi câu hỏi → BE gọi AI → trả lời
  ├─ Đã giải đáp → kết thúc hoặc tiếp tục hỏi
  └─ Cần nhân viên → khách chọn Gặp nhân viên
       → xác nhận cơ sở/hồ sơ liên quan
       → tạo yêu cầu hỗ trợ kèm lịch sử AI, đúng một lần
       → hàng chờ Staff của cơ sở
       → Staff nhận và chat trực tiếp
       → báo kết quả → khách xác nhận kết thúc
```

Không tạo SupportTicket hoặc thông báo Staff từ tin nhắn AI đầu tiên. Sau bàn giao, AI dừng trả lời thay nhân viên để tránh hai bên cùng xử lý.

## 3. Hiện trạng cần lưu ý

- `SupportApiWorkspace.tsx`, `SupportTicketDetail.tsx`, `SupportActionForm.tsx` đã cung cấp hội thoại, đính kèm, phân quyền và trao đổi PUBLIC/INTERNAL.
- `SupportCreateForm.tsx` hiện chuyển tin nhắn đầu thành payload tạo support workflow. BE `SupportService.create()` tạo ticket và thông báo Staff/Manager ngay; cần tách bước này khỏi chat AI ban đầu.
- `CustomerSupportChatbot.tsx` và `supportBotService.ts` cũ dùng FAQ/từ khóa, demo store và logic phân nhân viên cũ; chưa phải API AI thực sự.
- FAQ cũ có nội dung không còn khớp chính sách hiện tại. Không dùng nguyên bộ đó làm nguồn sự thật cho AI.
- Trường tác giả của tin nhắn support hiện có Customer/Staff/Manager. Cần biểu diễn AI rõ ràng ở lớp chat mới, không giả AI thành Staff hoặc Customer.
- Scope theo cơ sở vẫn bắt buộc: hội thoại Thủ Đức không được đưa cho Staff chỉ phụ trách Quận 1. Nếu thiếu nhân sự được phân công, cần phản hồi đúng tình trạng và báo quản lý.

## 4. Breakdown công việc

| ID | Loại | Công việc | Điều kiện hoàn thành |
| --- | --- | --- | --- |
| UI-01 | Sửa | Bỏ nav hỗ trợ của Customer, đặt launcher ở cấp CustomerApp/Layout phù hợp. | Mở chat từ mọi trang Customer mà không điều hướng sang trang hỗ trợ riêng. |
| UI-02 | Thêm | Panel chat nổi, mở/đóng/thu nhỏ, responsive và điều khiển bàn phím. | Đổi trang hoặc thu nhỏ không làm mất hội thoại/bản nháp; không che nút nghiệp vụ quan trọng. |
| UI-03 | Sửa | Đưa khung chat hiện có vào panel và hiển thị AI/khách/Staff rõ ràng. | Khách biết đang chat với AI, chờ Staff hay đã có người tiếp nhận; không báo online hoặc thời gian chờ giả. |
| UI-04 | Thêm | Nút Gặp nhân viên, chọn cơ sở/hồ sơ khi chưa xác định. | Có bước xác nhận đích bàn giao; nếu có nhiều Rental không tự chọn nhầm cơ sở. |
| AI-01 | Thêm BE | Adapter nhà cung cấp AI với API key phía server. | Có cấu hình bật/tắt, timeout, xử lý lỗi; không lộ key vào bundle FE hoặc log. |
| AI-02 | Thêm | Prompt, nguồn kiến thức và cách lấy ngữ cảnh được phép. | Trả lời bám chính sách hiện hành, phân biệt hướng dẫn với dữ liệu hồ sơ thực tế. |
| AI-03 | Thêm | Giới hạn lượt gọi, kích thước tin/lịch sử, số lần thử lại và lượng đầu ra. | Lỗi mạng không gây gửi lặp vô hạn hoặc phát sinh phản hồi trùng. |
| DATA-01 | Thêm DB | Lưu hội thoại AI và tin nhắn theo Customer, liên kết cơ sở/hồ sơ khi có. | Đăng nhập lại đọc được lịch sử đúng chủ sở hữu; có chính sách lưu/xóa dữ liệu được thống nhất. |
| API-01 | Thêm | API danh sách, tạo hội thoại, đọc/gửi tin AI và bàn giao. | Xác thực, kiểm tra ownership, phân trang, validation và idempotency phù hợp. |
| HANDOFF-01 | Thêm | Bàn giao hội thoại AI sang workflow support hiện có. | Một hội thoại chỉ tạo một ticket bàn giao; retry trả đúng ticket cũ. |
| HANDOFF-02 | Sửa | Staff xem tóm tắt và lịch sử trao đổi AI sau khi nhận đúng hội thoại. | Không bắt khách kể lại; vẫn giữ giới hạn nội dung trước claim và scope cơ sở. |
| HANDOFF-03 | Sửa | Đồng bộ trạng thái AI, chờ nhận, Staff xử lý và kết thúc. | Không có trạng thái giao diện sai với server; Staff chưa nhận thì không hiển thị đã kết nối. |
| KEEP-01 | Tái sử dụng | Hàng chờ, claim, chat Staff, file, notification, PUBLIC/INTERNAL, xác nhận kết quả. | Giữ hành vi và dữ liệu cũ; không tạo hệ thống phân công song song. |
| CLEAN-01 | Bỏ/thay | Form gửi đơn riêng, chatbot FAQ demo và logic tự chọn Staff từ roster cũ trong luồng Customer mới. | Chỉ còn một luồng hỗ trợ Customer; chỉ xóa file sau khi kiểm tra hết nơi sử dụng. |
| QA-01 | Sửa/thêm | Test FE, BE, migration, API và kịch bản end-to-end. | Đạt các tiêu chí ở mục 9 trước khi bật cho người dùng. |
| DOC-01 | Sửa | Contract, cấu hình mẫu và happy path support. | Phân biệt chat AI với ticket nội bộ được tạo khi bàn giao. |

## 5. API và dữ liệu dự kiến

Tên endpoint dưới đây là đề xuất để thảo luận, chưa phải contract đã triển khai.

| API | Mục đích |
| --- | --- |
| POST `/api/customer/chat/conversations` | Bắt đầu hội thoại AI. |
| GET `/api/customer/chat/conversations` | Liệt kê hội thoại thuộc khách hiện tại. |
| GET `/api/customer/chat/conversations/{id}/messages` | Đọc lịch sử có phân trang. |
| POST `/api/customer/chat/conversations/{id}/messages` | Lưu câu hỏi, gọi AI và trả kết quả. |
| POST `/api/customer/chat/conversations/{id}/handoff` | Chuyển Staff với đích cơ sở/hồ sơ đã xác nhận. |

- Tối thiểu bổ sung entity/bảng hội thoại và tin nhắn AI; hội thoại giữ liên kết ticket khi bàn giao.
- Trạng thái dự kiến: `AI_ACTIVE → HANDOFF_PENDING → WAITING_STAFF → STAFF_ACTIVE → CLOSED`. Chốt tên và điều kiện chuyển trước khi migration.
- Giữ trạng thái AI riêng với `SupportTicketStatus`; xác định mapping rõ ràng thay vì sửa các enum cũ chỉ để khớp tên UI.
- Nội dung AI có vai trò tác giả riêng. DTO/feed của widget có thể kết hợp lịch sử AI và support; không ép tin AI vào DTO chỉ cho Customer/Staff/Manager.
- Snapshot/tóm tắt bàn giao và liên kết lịch sử phải bền vững. Tạo ticket, gắn liên kết và chống trùng cần cơ chế nhất quán; không giữ transaction DB mở trong lúc chờ nhà cung cấp AI trả lời.
- MVP dùng request/response và polling hiện có. Streaming/WebSocket là bước sau nếu cần, không bắt buộc cho bản đầu.

## 6. Kiến thức và ngữ cảnh AI

| Nguồn | Cách sử dụng |
| --- | --- |
| Chính sách hiện hành | Nội dung được quản lý/kiểm tra, có phiên bản hoặc mốc cập nhật; không nạp nguyên ghi chú lịch sử mâu thuẫn. |
| Hướng dẫn thao tác | Đặt kho, xem biên lai, trả kho, gia hạn và liên hệ cơ sở; liên kết tới đúng màn hình. |
| Hồ sơ khách sở hữu | BE kiểm tra ownership và chỉ trả các trường cần để trả lời câu hỏi. |
| Tin nhắn hội thoại | Giới hạn lịch sử đưa vào model; tách chỉ dẫn hệ thống với nội dung người dùng. |
| PUBLIC/INTERNAL | Không đưa tin/tệp INTERNAL của Staff–Manager vào ngữ cảnh AI phục vụ Customer. |

Không đưa mật khẩu, mã truy cập, giấy tờ định danh hoặc thông tin thanh toán nhạy cảm không cần thiết tới nhà cung cấp AI. Không để nội dung chat tự cấp quyền gọi API hoặc thay chỉ dẫn hệ thống.

## 7. Quy tắc bàn giao

- Khách chủ động yêu cầu gặp nhân viên.
- Vấn đề cần kiểm tra thực tế, đối soát, xử lý khiếu nại hoặc thao tác có thẩm quyền.
- AI không có nguồn trả lời đáng tin cậy, thiếu dữ liệu hoặc liên tiếp gặp lỗi.
- AI đề nghị bàn giao theo quy tắc rõ ràng; không dùng một điểm tự đánh giá độ tin cậy của model làm điều kiện duy nhất.
- Khi chưa có cơ sở, khách chọn cơ sở hoặc hồ sơ trước khi tạo ticket. Khi không có Staff được phân công, lưu trạng thái chờ đúng thực tế và thông báo quản lý, không tự mở rộng scope.
- Lưu tóm tắt vấn đề và lịch sử cần thiết; tránh tạo ticket/thông báo trùng khi bấm nhiều lần hoặc retry.
- AI trả lời xong không tự chứng nhận kết quả tài chính/nghiệp vụ đã hoàn tất.

## 8. Thứ tự triển khai

1. **Chốt thiết kế:** Customer-only launcher; vai trò Staff/Manager; provider/model; nguồn kiến thức; quyền đọc dữ liệu; quy tắc và trạng thái bàn giao.
2. **Widget:** Launcher và panel dùng chung; giữ trạng thái khi đổi trang; thay nav Customer; nối lại thông báo/deep link để mở đúng hội thoại.
3. **Hội thoại AI:** Migration và API lưu tin; adapter AI server-side; timeout, retry và giới hạn sử dụng; trả lời từ kiến thức đã kiểm tra.
4. **Bàn giao:** Liên kết SupportService, scope cơ sở, chống trùng, chuyển transcript và tóm tắt cho Staff; dừng AI khi sang người thật.
5. **Hoàn thiện:** Attachment theo phạm vi, lịch sử cũ, trạng thái lỗi/chờ/ngoài giờ, test và tài liệu; sau đó mới cân nhắc streaming.

## 9. Tiêu chí nghiệm thu

- Customer không còn nav hỗ trợ, icon chat dùng được trên các trang Customer và trên màn hình nhỏ.
- Tin đầu của chat AI không tạo ticket hoặc báo Staff. AI trả lời từ BE, key không có trên FE.
- Hỏi đáp thông thường kết thúc được mà không cần nhân viên.
- Bàn giao đúng một lần, đúng cơ sở; Staff đọc được lịch sử liên quan sau khi có quyền.
- Tải lại/đổi trang không mất hội thoại; lỗi AI vẫn giữ câu hỏi và cho thử lại/chuyển Staff.
- Không đọc được hội thoại khách khác, file khác hồ sơ hoặc nội dung INTERNAL.
- AI không tự tạo giao dịch tài chính, gia hạn, hủy hay đổi quyền truy cập.
- Test bao phủ ownership/scope, gửi trùng, timeout, retry, concurrent handoff/claim, migration và bảo toàn dữ liệu cũ.
- Happy path: mở icon → AI trả lời → khách yêu cầu nhân viên → chọn cơ sở → Staff nhận → chat trực tiếp → xác nhận kết quả.

## 10. Công cụ hỗ trợ

Đã rà soát catalog skill chính thức và danh mục plugin ngày 23/10/2026. Công cụ hỗ trợ phát triển không thay thế cấu hình API AI, tài khoản nhà cung cấp hoặc API key của ứng dụng.

### Skill có sẵn trong phiên

| Skill | Khi sử dụng | Ghi chú |
| --- | --- | --- |
| `vercel:react-best-practices` | Xây widget, quản lý state/draft, polling, keyboard và accessibility. | Phù hợp React/TypeScript hiện tại, không cần chuyển framework. |
| `vercel:verification` | Kiểm tra FE → API Java → DB → phản hồi và bàn giao Staff. | Cần chứng cứ từng bước; không thay thế unit/integration test. |
| `computer-use:computer-use` | Xem giao diện thật, thao tác chat và kiểm tra responsive. | Đang có skill; cần trình duyệt được kết nối. Lần kiểm tra UI trước chưa có browser khả dụng. |
| `openai-docs` | Tra tài liệu chính thức nếu chọn OpenAI làm provider. | Đã có skill hệ thống; không cài thêm bản trùng chỉ vì công cụ liệt kê curated báo chưa cài vào thư mục người dùng. |

### Skill tìm được và trạng thái bổ sung

| Skill | Mức ưu tiên | Công dụng và giới hạn |
| --- | --- | --- |
| [`playwright`](https://github.com/openai/skills/tree/main/skills/.curated/playwright) | Đã cài ngày 23/10/2026 | Thao tác/chụp ảnh/debug luồng UI thật bằng CLI. Cần Node/npx và runtime trình duyệt; kiểm tra môi trường Windows/wrapper trước khi dùng. Không tự coi có skill là đã có browser hoạt động. |
| [`frontend-design`](https://github.com/anthropics/skills/tree/main/skills/frontend-design) | Đã cài ngày 23/10/2026 | Thiết kế bố cục, typography, màu sắc, nội dung và trạng thái UI theo ngữ cảnh sản phẩm. Khi áp dụng giữ nhận diện StorageHub và tái sử dụng component hiện có. |
| [`figma-implement-design`](https://github.com/openai/skills/tree/main/skills/.curated/figma-implement-design) | Đã cài ngày 23/10/2026 | Chuyển bản thiết kế Figma thành code phù hợp project. Chỉ dùng khi có thiết kế và kết nối Figma khả dụng. |
| [`security-threat-model`](https://github.com/openai/skills/tree/main/skills/.curated/security-threat-model) | Cao trước khi tích hợp dữ liệu khách | Phân tích ranh giới Customer/AI/Staff, truy cập chéo, prompt injection và dữ liệu gửi provider. Dùng khi thực hiện task threat modeling riêng. |
| [`gh-fix-ci`](https://github.com/openai/skills/tree/main/skills/.curated/gh-fix-ci) | Khi có CI lỗi | Đọc checks/log GitHub Actions và xác định lỗi. Cần GitHub CLI có đăng nhập/quyền repo khi dùng workflow CLI. |
| [`security-best-practices`](https://github.com/openai/skills/tree/main/skills/.curated/security-best-practices) | Bổ trợ | Có hướng dẫn cho TypeScript/JavaScript, Python, Go; dùng cho FE phù hợp. Không coi skill này bao phủ đầy đủ Java/Spring Boot; BE vẫn cần review/test quyền và tích hợp riêng. |

`vercel:ai-sdk` có sẵn nhưng không phải phụ thuộc bắt buộc: BE hiện là Java/Spring Boot. Chỉ áp dụng nếu có quyết định dùng AI SDK ở tầng JavaScript/TypeScript; không thêm một backend mới chỉ để dùng skill.

### Plugin

| Plugin | Trạng thái tra cứu | Vai trò |
| --- | --- | --- |
| GitHub | Đã cài/có công cụ trong phiên. | Đọc repo/PR/checks và hỗ trợ CI; thao tác cần đúng quyền repo. Git CLI hiện đã fetch/merge thành công. |
| OpenAI Developers | Tìm thấy, chưa cài/kết nối được xác nhận; đã đề xuất để người dùng lựa chọn. | Hỗ trợ tài liệu và cách tích hợp nếu chọn OpenAI. Không tự cấp API key hoặc tín dụng API. |
| Figma | Tìm thấy, chưa cài. Tùy chọn. | Chỉ cần khi muốn thiết kế/review widget bằng file Figma; không bắt buộc cho MVP hiện tại. |
| Product Design | Tìm thấy, đã đề xuất; chưa xác nhận cài/kết nối. | Khảo sát luồng người dùng và tạo prototype để review trước khi triển khai. |

Ưu tiên dùng skill React/verification hiện có, kết hợp ba skill UI đã cài theo yêu cầu bổ sung công cụ. Skill mới có thể được sử dụng từ lượt trò chuyện tiếp theo. Chưa xác nhận kết nối plugin mới và chưa chọn provider/model AI. Việc cài skill nằm trong môi trường Codex, không thêm dependency vào FE/BE.

Kiểm tra công cụ cục bộ: đã có `npx`; chưa tìm thấy GitHub CLI (`gh`) trên PATH. Cần cài và xác thực `gh` trước khi dùng workflow CLI của `gh-fix-ci`. Có `bash.exe` nhưng chưa xác minh runtime/wrapper Playwright hoạt động.

**Cập nhật lựa chọn 23/10/2026:** người dùng chọn **Gemini**. Các ghi chú “chưa chọn provider” ở trên mô tả thời điểm lập kế hoạch; thiết kế và tiến độ hiện tại xem mục 12.

## 11. Mốc cập nhật source và bảo toàn công việc

- Sau khi lưu kế hoạch, đã fetch và fast-forward `develop` vào nhánh hiện tại. Sau khi nhập thêm commit develop xuất hiện trong lúc kiểm tra: FE `feature/fe-duong` tại `be42fed`, BE `feature/be-duong` tại `7047cd6`.
- Đã khôi phục phần chat, test và tài liệu chưa commit sau merge, không có conflict. Có bản sao file trong thư mục `.local` ở workspace và stash dự phòng của mỗi repo.
- Chưa push nhánh hoặc commit các tài liệu/chat đang phát triển trong task này.
- Cần đọc lại thay đổi quyền từ develop trước khi triển khai bàn giao Staff; không dựa vào bộ quyền cũ được mô tả trong tài liệu lịch sử.

### Kiểm tra sau merge

- FE tại `be42fed`: 1.154 test đạt, 7 lỗi, 2 bỏ qua. Lỗi nằm ở `tests/managerRules.test.ts` (quyền Manager), `src/views/manager/managerLayoutBadge.test.tsx` (4 nhãn vai trò), `src/services/returnApi.test.tsx` (2 trường hợp quyền/contract trả kho).
- Typecheck FE tại `be42fed`: lỗi `TS2367` ở `src/views/ProfileView.tsx:1062`, so sánh vai trò `business` với `admin` sau khi TypeScript đã thu hẹp kiểu.
- Các file FE liên quan các lỗi trên trùng với `origin/develop`; không có sửa cục bộ ở các file đó. Chưa chỉnh nghiệp vụ hoặc test của develop trong task lập kế hoạch/merge này.
- BE tại `7047cd6`, Java 21 và UTC: 959 test, 2 assertion failure, 9 error, 50 bỏ qua; bộ test chưa đạt. Các nhóm lỗi: quyền/scope và fixture vai trò, nguồn lịch ký không tìm thấy Staff phù hợp, quy tắc hoàn tiền/thu tiền, mã phản hồi API 403 so với kỳ vọng 404, và Flyway báo trùng version `2026101901` trong bài kiểm tra migration. Chỉ ghi nhận kết quả, chưa sửa các nhóm lỗi này trong task lập kế hoạch/merge.
- Riêng lỗi Flyway trùng version: log trỏ tới `target/classes/db/migration/V2026101901__renewal_installment_payment_quotes.sql`, trong khi source hiện đặt file tương ứng dưới `db/migration-history/develop-renewal-1901`. Đây là dấu hiệu resource còn sót ở thư mục build sau merge; cần xác minh bằng `clean test` trước khi kết luận migration source bị trùng. Lượt kiểm tra trên dùng `test`, chưa dùng `clean test`.
- Log kiểm tra nằm tại `.local/ai-plan-latest-fe-tests.log`, `.local/ai-plan-latest-fe-typecheck.log` ở workspace; BE tại `target/ai-plan-latest-tests.log`.

## 12. Triển khai theo từng bước có kiểm soát

Cập nhật: 23/10/2026. Mỗi lượt triển khai tập trung một bước ở mục 8, ghi phạm vi, thay đổi, bằng chứng kiểm tra và việc còn lại trước khi chuyển bước. Không đánh dấu hoàn tất chỉ vì đã viết code. Không gộp sửa lỗi nền của develop vào tính năng nếu chưa xác định chúng cản trở bước đang làm.

### 12.1. Bảng tiến độ

| Bước | Trạng thái | Điều kiện qua bước |
| --- | --- | --- |
| 1 — Thiết kế | Đã đối chiếu source, có thiết kế để review; người dùng đã chọn Gemini | Review các quyết định dưới đây; model, bộ kiến thức và retention phải chốt trước bước 3. |
| 2 — Widget | Đang triển khai: widget API riêng, không sửa demo | Đã thêm launcher/panel và nối chat Staff; còn nav/deep link và nghiệm thu UI thực tế trước khi hoàn tất toàn bước. |
| 3 — Hội thoại AI | Đang triển khai: adapter/API stateless, mặc định tắt | Còn nối FE, xác nhận provider, migration và lưu lịch sử/ownership bền vững; chưa hoàn tất bước 3. |
| 4 — Bàn giao | Chưa triển khai | Một ticket/hội thoại, đúng cơ sở, transcript đúng quyền, đồng bộ claim/resolve/reopen/close, dừng AI. |
| 5 — Hoàn thiện | Chưa triển khai | Đạt mục 9, ghi bằng chứng E2E và cấu hình triển khai; phân biệt lỗi nền với lỗi phát sinh. |

### 12.2. Thiết kế bước 1

Các quyết định kỹ thuật dưới đây là thiết kế đề xuất dựa trên source, chưa phải hành vi đã triển khai.

**Vị trí widget và giữ dữ liệu**

- Gắn widget một lần trong `self-storage-fe/src/views/customer/CustomerApp.tsx`, ngoài các nhánh render theo `page`. Không gắn vào Layout dùng chung cho mọi vai trò.
- State mở/thu nhỏ, conversation/ticket đang chọn và draft nằm ở widget/provider còn mounted khi đổi trang. Đóng panel chỉ ẩn giao diện; không hủy draft. Reset và hủy request/polling khi đổi tài khoản hoặc logout.
- Sau bước 3, lịch sử do server lưu theo Customer. Không hứa phục hồi draft sau reload ở bước 2; đổi trang/thu nhỏ phải giữ draft. Không lưu transcript nhạy cảm vào localStorage để giả lập persistence.
- Bỏ nav hỗ trợ Customer; các nút mở hỗ trợ, thông báo SUPPORT và đường dẫn cũ phải mở widget. Giữ nguyên màn hình Staff/Manager.
- `CustomerApp.tsx` hiện có nav `support`, shortcut overview, mapping notification sang `support`, nhánh `page === 'support'` và chatbot demo chỉ chạy khi chưa API-authenticated. Phải rà đủ các điểm này trước khi xóa luồng cũ.
- Ticket cũ vẫn mở qua `openTicket` của `SupportApiRoute`/`SupportApiWorkspace`; không tự chuyển ticket cũ thành hội thoại AI. Tra ngược ticket sang conversation sau bước 4 chỉ khi có liên kết do server cung cấp.
- Bước 2 dùng luồng chat Staff hiện tại trong panel và hiển thị đúng chức năng hiện có. Chỉ bật trải nghiệm AI-first sau khi bước 3–4 đạt kiểm tra; không gắn nhãn AI cho phản hồi demo hoặc ticket được tạo trực tiếp.

**Vai trò và ranh giới truy cập**

- Customer đọc/gửi trong hội thoại của mình. Mọi ID conversation, ticket và hồ sơ liên quan đều phải kiểm tra ownership ở BE; không tin customerId từ FE.
- Staff cần tài khoản active, quyền support hiện hành và scope cơ sở phù hợp; hàng chờ chỉ có metadata cho phép. Chỉ sau claim hợp lệ mới đọc nội dung/transcript của ticket đã nhận. Kiểm tra lại quyền hiện tại khi đọc/gửi, không chỉ khi claim.
- Manager xem theo scope và quyền hiện tại, không claim/phân công/resolve thay Staff. Source có ngoại lệ cho tin INTERNAL sau khi Staff đã nhận, với quyền cập nhật và scope MANAGE; widget Customer không được lộ nội dung này.
- Nguồn đối chiếu: `SupportService.authorize`, `visible`, `queue`, `claim`, `managerMessage`, `linkedFacility`; không dùng mô tả phân công cũ để mở rộng quyền.

**Gemini và ngữ cảnh**

- Provider: **Gemini**, theo lựa chọn của người dùng. Adapter nằm trong Java/Spring Boot hiện tại; FE chỉ gọi API ứng dụng.
- Dự kiến dùng Google Gen AI Java SDK qua adapter riêng. Key chỉ đọc từ biến môi trường phía server; không ghi key, transcript hoặc response thô của provider vào log. Cấu hình ứng dụng phải bind key một cách tường minh để tránh chọn nhầm khi có nhiều biến môi trường.
- Model là cấu hình bắt buộc khi bật AI; chốt model/version và SDK tương thích trước bước 3 dựa trên model thực sự khả dụng cho tài khoản. Không tự fallback sang model khác hoặc bật tính phí ngoài cấu hình.
- AI mặc định tắt. Khi tắt, lỗi hoặc thiếu cấu hình, UI báo đúng tình trạng và cho chọn gặp Staff; không trả FAQ demo như câu trả lời của Gemini.
- MVP ưu tiên hướng dẫn từ bộ kiến thức có version, truy vết tới contract hiện hành. Không lấy toàn bộ `docs/`, FAQ cũ hoặc nội dung lịch sử làm prompt. Các contract là nguồn để biên soạn, chưa mặc nhiên là bộ kiến thức đã duyệt.
- Tra dữ liệu hồ sơ bằng projection chỉ đọc, sau kiểm tra ownership; chỉ thêm từng loại dữ liệu khi có test quyền và danh sách trường được phép. Không cấp model công cụ ghi nghiệp vụ. Không gửi INTERNAL, giấy tờ định danh, mã truy cập hoặc dữ liệu thanh toán nhạy cảm tới provider.
- Tham chiếu chính thức đã tra ngày 23/10/2026: [Google Gen AI Java SDK](https://github.com/googleapis/java-genai), [quản lý Gemini API key](https://ai.google.dev/gemini-api/docs/api-key). Phiên bản SDK/model cụ thể chưa được chốt hoặc cài đặt.

**Trạng thái và bàn giao**

Giữ `SupportTicketStatus` hiện tại. `HANDOFF_PENDING` biểu diễn hội thoại server đang chờ xác nhận đích bàn giao, chưa có ticket; có thể hủy để trở lại AI. Khi bàn giao, khóa/kiểm tra trạng thái trong transaction ngắn, tạo ticket và liên kết duy nhất một cách nguyên tử. Không gọi Gemini trong transaction đó.

| Trạng thái hội thoại đề xuất | Điều kiện và hiển thị |
| --- | --- |
| `AI_ACTIVE` | Chưa có ticket; được hỏi AI. |
| `HANDOFF_PENDING` | Khách bắt đầu yêu cầu Staff; đang xác nhận cơ sở/hồ sơ, chưa tạo ticket. Tạm dừng gửi AI; hủy thì về `AI_ACTIVE`. |
| `WAITING_STAFF` | Đã có ticket `open`, chưa có Staff nhận hợp lệ. Không hiển thị đã kết nối; không gọi AI. |
| `STAFF_ACTIVE` | Ticket `in_progress` hoặc `waiting_customer`, Staff đã nhận hợp lệ; UI hiển thị riêng khi cần khách bổ sung. |
| `RESOLUTION_PENDING` | Ticket `resolved`: đã báo kết quả, chờ khách xác nhận hoặc quy tắc tự đóng hiện hành. Không coi là `CLOSED`. |
| `CLOSED` | Ticket `closed`, hoặc khách chủ động kết thúc hội thoại chỉ có AI. Không nhận thêm tin; theo luồng tạo hội thoại mới/follow-up hợp lệ. |

- `RESOLUTION_PENDING` bổ sung cho chuỗi dự kiến ở mục 5 để khớp workflow thực tế. Reopen từ `resolved` trở về trạng thái suy ra từ ticket: `STAFF_ACTIVE` nếu giữ Staff hợp lệ, hoặc `WAITING_STAFF` nếu về hàng chờ. Không tự quay lại AI sau khi đã tạo ticket.
- Khi AI đang trả lời mà khách bàn giao, kết quả về muộn phải được kiểm tra lại trạng thái/version trước khi ghi; không cho AI chen vào sau khi đã chuyển Staff.
- Cơ sở từ hồ sơ phải thuộc khách và khớp cơ sở đã xác nhận; thiếu hồ sơ thì khách chọn cơ sở active. Không tự chọn Rental đầu tiên khi có nhiều hồ sơ.
- Ràng buộc DB bảo đảm mỗi conversation chỉ liên kết một ticket; idempotency bảo đảm retry cùng yêu cầu trả ticket cũ, payload khác gây conflict. Chỉ có idempotency key ở FE là chưa đủ để chống double-click với hai key khác nhau.
- Dùng lại hàng chờ và notification hiện có. Không có Staff phù hợp vẫn giữ `WAITING_STAFF`, báo Manager theo scope hiện hành; không chuyển cơ sở. Snapshot/transcript bàn giao được lưu bền vững, đọc qua quyền ticket sau claim; không nhét AI thành tác giả CUSTOMER/STAFF trong bảng tin cũ.

### 12.3. Điểm còn mở và kiểm tra tiếp theo

| Việc còn mở | Phải xong trước | Cách xử lý |
| --- | --- | --- |
| Model Gemini và phiên bản SDK | Bước 3 | Xác minh khả dụng với tài khoản, hỗ trợ Java, timeout và giới hạn đầu ra; cấu hình phía BE. Không gửi API key qua chat. |
| Bộ kiến thức phiên bản đầu | Bật trả lời AI ở bước 3 | Liệt kê từng hướng dẫn, nguồn contract, ngày kiểm tra và nội dung được duyệt; thiếu nguồn thì đề nghị Staff. |
| Thời hạn lưu/xóa hội thoại và dữ liệu gửi provider | Migration/bật AI ở bước 3 | Thống nhất thời hạn, xử lý hội thoại đã bàn giao và cơ chế xóa; không tự đặt chính sách lưu vĩnh viễn. |
| Giới hạn tin, lịch sử, tần suất và timeout | Contract bước 3 | Ghi giá trị cấu hình cụ thể và test cả ngưỡng vượt giới hạn; không retry vô hạn. |

Kiểm tra trong bước 1: đã đọc các điểm tích hợp Customer, controller, enum trạng thái và logic quyền/claim/create của SupportService; chưa sửa runtime, chưa chạy lại test FE/BE. Kết quả test ở mục 11 là baseline lịch sử, không phải kết quả của lượt này. Working tree có thay đổi chat/tài liệu từ trước và được giữ nguyên.

Phạm vi lượt triển khai tiếp theo: **chỉ bước 2 — Widget (UI-01/UI-02 và phần UI-03 cho chat Staff hiện tại)**. Chưa thêm migration hay gọi Gemini. Nghiệm thu bằng test state/draft khi đổi trang, mở đúng ticket từ thông báo/đường dẫn cũ, role Customer, keyboard và mobile; báo riêng các kiểm tra chưa thực hiện được.

### 12.4. Widget API riêng theo yêu cầu giao diện

- Người dùng yêu cầu giữ riêng chatbot demo, viết icon và panel mới sạch, cân đối với giao diện StorageHub. Lượt này không sửa `CustomerSupportChatbot.tsx` hoặc `supportBotService.ts`; các chỉnh màu/icon demo ở lượt trước vẫn là thay đổi trước đó.
- Thêm `src/components/support/CustomerSupportWidget.tsx`, chỉ render cho Customer đăng nhập API có quyền support; gắn ở `CustomerApp` ngoài nhánh trang. Widget có icon tròn hổ phách, panel trắng/xám đá, danh sách hội thoại, tạo/đọc/gửi qua API support hiện hành; không gọi Gemini hoặc dùng demo store.
- Panel dùng portal để tránh bị layout cắt; mở/thu nhỏ và Escape giữ nội dung mounted, giữ draft trong phiên; reset theo actor. Trạng thái lỗi, upload và retry dùng cơ chế support hiện có. Chưa thay nav/deep link hoặc chuyển sang AI-first.
- Build FE đạt; 4 test widget mới và 23 test chat/route/upload hiện có đạt. Chưa nghiệm thu trực tiếp trên trình duyệt vì inventory browser hiện không có browser kết nối. Typecheck cuối chỉ còn lỗi nền `TS2367` ở `ProfileView.tsx:1062`, không báo lỗi widget mới.

### 12.5. Mở widget vào trợ lý FAQ trước

- Theo yêu cầu tiếp theo, widget mặc định mở màn hình trợ lý với lời chào, gợi ý câu hỏi và ô nhập; kênh Nhân viên chỉ được mount khi khách chọn. Thu nhỏ hoặc đổi kênh giữ nội dung/draft trong phiên.
- Thêm `supportAssistantFaq.ts` và `CustomerSupportAssistant.tsx`: tách các chủ đề FAQ từ demo thành bộ riêng, không import demo store/roster hoặc sửa luồng demo. Điều chỉnh nội dung hướng dẫn để không cam kết chính sách cũ, tự xác nhận số tiền, thời gian hoàn tiền hay tình trạng nhân viên.
- Đây là bước giao diện và FAQ nền theo yêu cầu, **chưa phải tích hợp Gemini**. UI ghi rõ “Hướng dẫn tự động theo FAQ”; câu trả lời hiện dùng khớp từ khóa, không gọi model. Câu không khớp đề nghị gặp nhân viên, không giả đã tra hồ sơ.
- Hỏi FAQ không gọi API tạo ticket. Nút Gặp nhân viên mở kênh support hiện có; khách vẫn phải chọn cơ sở/hồ sơ và gửi yêu cầu. Chưa tự chuyển transcript FAQ sang ticket; persistence và handoff AI bền vững vẫn thuộc bước 3–4.
- Theo yêu cầu của người dùng, lượt này không chạy test tự động. Kết quả build/test ở mục 12.4 là của lượt trước, không xác nhận thay đổi FAQ này.

### 12.6. Cấu hình Gemini tham chiếu từ cultural-museum

- Theo yêu cầu, đã đọc cấu hình từ `D:\project\cultural-museum` và trích riêng key, base URL, model, temperature sang `.local/gemini.local.properties` trong repo BE. Đích được xác minh Git-ignore trước khi ghi; không đưa key vào tài liệu, FE hoặc output công cụ. Không sửa project nguồn.
- Giá trị tham chiếu: endpoint `https://generativelanguage.googleapis.com/v1beta/openai`, model `gemini-3.5-flash`, temperature `0.7`. Đây là giá trị trong project nguồn, chưa chứng minh model khả dụng hoặc key hoạt động. Chưa gửi request tới Google.
- File đích dùng namespace dự kiến `storagehub.ai.*`, `enabled=false`, chưa được import/bind bởi ứng dụng. Widget vẫn đang trả FAQ, chưa gọi Gemini. Trước khi tích hợp phải chọn adapter, bind cấu hình, timeout/giới hạn và xử lý lỗi; không mặc nhiên coi chỉ sao chép file là hoàn thành AI-01.
- Project nguồn dùng Spring Boot `3.4.3`, Spring AI `1.0.0`; BE hiện tại dùng Spring Boot `4.1.1`. Không sao chép nguyên dependency hoặc bộ RAG/PGVector và prompt bảo tàng vào StorageHub. Embedding không cần cho FAQ MVP hiện tại.
- Lượt này không chạy test hoặc gọi API phát sinh sử dụng provider.

### 12.7. Adapter Gemini và API backend stateless

- Thêm `POST /api/customer/chat/replies`, yêu cầu JWT Customer active (đối chiếu DB) và header UUID `Idempotency-Key`. Body và response được mô tả trong contract support chính; không tạo contract phụ.
- Adapter Java HttpClient gọi endpoint OpenAI-compatible của **Gemini**, không thêm thư viện Spring AI hoặc backend mới. Config `storagehub.ai.*` bind vào `SupportAiProperties`, key server-only; không tự import file local hay tự bật tính năng.
- Prompt/FAQ version `support-faq/2026-10-23-v1` ở `src/main/resources/ai/support-faq-v1.txt`. Chỉ hướng dẫn chung, không có công cụ gọi nghiệp vụ hoặc tra dữ liệu hồ sơ. Client không được truyền role system/tool; lịch sử user/assistant đều được coi là ngữ cảnh không đáng tin.
- Giới hạn: 1.000 ký tự/tin, 10 tin history, 8.000 ký tự tổng, 64 KiB body; mặc định 768 token đầu ra, 4.000 ký tự answer, 64 KiB response provider, timeout tổng 15 giây và không tự retry. Rate limit 10 request mới/phút/Customer, tối đa 8 call đồng thời. Idempotency/replay 10 phút trong RAM, bao gồm kết quả lỗi; chưa bảo đảm qua restart/replica.
- Mã lỗi tắt tính năng/cấu hình, timeout, quota và provider được chuẩn hóa; không trả response Google hoặc raw exception. Chưa có lọc/redaction tổng quát cho dữ liệu nhạy cảm do khách tự nhập, chưa bật cho người dùng thật.
- Đã biên dịch riêng toàn bộ mã Java mới và ErrorCode bằng `javac --release 21` với Lombok/dependency hiện có (bản sao JAR trong `.local/support-ai-compile-deps`): exit 0. Maven compile toàn repo gặp `AccessDeniedException` khi đóng JAR `spring-boot-4.1.1.jar` trong cache người dùng; không coi lượt incremental “nothing to compile” là bằng chứng build toàn repo đạt. Argfile/kết quả biên dịch riêng nằm trong `.local/`, không commit.
- Không chạy test theo yêu cầu; không gọi Google, không xác nhận model/key thực tế, không sửa widget hoặc chatbot demo ở lượt này. Chưa có migration/lưu hội thoại hay handoff. Lượt tiếp theo nối widget vào API, sau đó mới nghiệm thu provider/persistence theo các điều kiện còn mở.

### 12.8. Nối widget vào API Gemini

- `CustomerSupportAssistant.tsx` gọi `supportAiApi.ts` → `POST /api/customer/chat/replies` bằng JWT và UUID idempotency. FAQ ở FE chỉ cung cấp chủ đề gợi ý; toàn bộ câu trả lời mới lấy từ backend, không fallback demo/khớp từ khóa.
- Thêm trạng thái đang trả lời, giữ câu hỏi khi lỗi, kiểm tra lại cùng request và thông báo câu trả lời bị giới hạn độ dài. Giới hạn history đồng bộ với BE; chống double-click bằng lock đồng bộ, bỏ response về muộn sau khi unmount/đổi Customer.
- Khóa Gemini vẫn chỉ trong `.local/gemini.local.properties`, được Git-ignore. Nối optional import file đó và bật AI trong file local để nạp sau lần restart backend tiếp theo; không bật mặc định trong code được commit. Chưa gọi Google hoặc xác nhận key/model thực tế.
- Build FE đạt; không chạy test theo yêu cầu. Chưa xác nhận end-to-end với backend đã restart hoặc nghiệm thu browser. Chưa hoàn tất bước 3: còn xác nhận provider, persistence và các điều kiện dữ liệu ở mục 12.3. Chatbot demo không thay đổi trong lượt này.

### 12.9. Hiệu ứng mở/thu nhỏ widget

- Panel mới hiện dần, trượt 14px và scale nhẹ từ góc dưới phải khi mở (220ms); hiệu ứng ngược khi thu nhỏ (180ms). Launcher chuyển opacity/scale đồng bộ, không dùng timer hoặc unmount nên giữ nội dung/draft và có thể đảo chiều khi thao tác nhanh.
- Panel thu nhỏ dùng `inert`/`aria-hidden`, không giữ focus hoặc nhận click; focus trở về launcher. Tắt chuyển động khi `prefers-reduced-motion` được bật. Chỉ chỉnh widget API và CSS riêng, không sửa chatbot demo.
- Build FE đạt; không chạy test hoặc kiểm tra trực quan browser trong lượt này.

### 12.10. Chỉnh bố cục widget theo review giao diện

- Màn hình trợ lý dùng grid ba hàng: banner bảo mật trong layout flow, vùng tin nhắn `minmax(0,1fr)` cuộn riêng và composer. Bỏ `h-full` lồng nhau, không tự cuộn xuống cuối lời chào khi mới mở; lời chào rút gọn.
- Thay 4 card gợi ý bằng chip ngắn trong vùng tin nhắn, chỉ xuất hiện trước câu hỏi đầu tiên. Thu gọn ô nhập và bỏ liên kết Gặp nhân viên ở đáy; giữ lựa chọn Nhân viên cạnh Trợ lý làm đường chuyển kênh duy nhất.
- FAB của widget API dùng `display:none` khi panel mở (thay hiệu ứng fade FAB ở mục 12.9), thêm nút Đóng ✕ cạnh Thu nhỏ; cả hai giữ nội dung phiên. Panel vẫn có hiệu ứng mở/đóng và reduced-motion.
- Palette trắng/xám, bubble trợ lý nền xám nhạt; hổ phách làm điểm nhấn tab đang chọn/nút gửi. Thông báo Gemini và giới hạn lưu phiên vẫn hiển thị.
- Build FE đạt; không chạy test theo yêu cầu. Chưa xác nhận trực quan vì inventory trình duyệt kết nối vẫn rỗng. Không thay chatbot demo.

### 12.11. Bỏ nút retry và xử lý Gemini không phản hồi

- Theo yêu cầu, bỏ cả Kiểm tra lại câu hỏi và Gửi câu hỏi khác. Lỗi chỉ hiển thị thông báo, ô nhập mở lại; câu hỏi thất bại vẫn ở lịch sử UI. Không tự retry. Khi khách chủ động gửi lại câu hỏi chưa rõ kết quả do lỗi mạng, tái dùng key trong 9 phút để tránh gửi trùng; lỗi server đã xác định cho phép lần gửi chủ động mới.
- Chẩn đoán trực tiếp bằng câu hỏi mẫu không chứa dữ liệu Customer: key đọc được model list; `gemini-3.5-flash` trả HTTP 503 vì quá tải. Model `gemini-2.5-flash-lite` trả 404 qua endpoint này, không được chọn. `gemini-3.5-flash-lite` trả 200, có text và finish reason stop.
- Chuyển **file local Git-ignore** sang `gemini-3.5-flash-lite`, temperature 0.2, timeout 25 giây. Xác nhận thêm bằng chính adapter Java: có câu trả lời, khoảng 4.479ms; không dùng transcript khách để chẩn đoán. Không thêm model fallback tự động.
- Backend map Google 503 sang AI_UNAVAILABLE; thêm log chẩn đoán status/model/loại lỗi, không log key, raw response hoặc nội dung chat. Build FE và Maven compile BE đạt; không chạy test suite. Restart BE bằng runner tích hợp có sẵn để nạp cấu hình mới; log tại `.local/support-ai-backend-lite-restart.out.log`. Lượt restart đầu ở log `support-ai-backend-lite.out.log` gặp cổng 8080 còn bị tiến trình cũ giữ; đã xác minh theo Maven cha và dừng đúng backend cũ trước lượt restart sau.

### 12.12. Gom lưu ý vào icon thông tin

- Thêm icon thông tin bên phải thanh chọn Trợ lý/Nhân viên. Hover, focus bàn phím hoặc chạm vào icon để xem tooltip riêng của kênh; Escape đóng tooltip trước khi đóng panel.
- Bỏ banner Gemini khỏi vùng trợ lý và chuyển nội dung bảo mật vào tooltip; bỏ card giới thiệu lớn ở tab Nhân viên, giữ nút Cuộc trò chuyện mới và chuyển hướng dẫn vào tooltip tương ứng. Không đổi hành vi API, session hoặc demo.
- Build FE đạt; không chạy test theo yêu cầu.

### Cải thiện định dạng, prompt ngắn và FAQ trả nhanh

- Thêm `SupportReplyText.tsx` cho chữ đậm, đoạn và danh sách dạng React text node; không dùng HTML hoặc tự render URL/image. Tin Customer vẫn giữ text nguyên văn.
- Prompt yêu cầu đáp án hướng dẫn ngắn 2–4 câu, bỏ chào lặp/lời mời gặp nhân viên không cần thiết. Không bỏ điều kiện nghiệp vụ hoặc ép cắt đáp án để lấy tốc độ.
- Thêm `SupportQuickFaq` ở BE: 10 câu chuẩn hướng dẫn chung, khớp chính xác sau chuẩn hóa; không suy đoán bằng từ khóa. FAQ bỏ qua gọi Google, hiển thị “Hướng dẫn nhanh”; câu khác vẫn dùng Gemini. Response thêm source FAQ/GEMINI; kiểm tra quyền, validation, rate limit và idempotency vẫn giữ.
- Thêm đo thời gian trả lời theo source, không log dữ liệu khách. Streaming chưa thuộc lượt này. Build FE đạt; không chạy test suite theo yêu cầu.
