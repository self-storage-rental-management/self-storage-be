# Contract tích hợp sổ tài chính và thông báo

Cập nhật: 11/10/2026. Contract của code hiện tại; **không phải xác nhận đã triển khai DB chung**.

## 1. Bật module và tương thích

Hai module độc lập, mặc định tắt:

- `storagehub.integration.ledger.enabled=true`: đăng ký sổ tài chính, query và provider P01/P06/P14.
- `storagehub.integration.communication.enabled=true`: đăng ký hàng đợi thông báo, worker, policy và provider P16/P18/ClosePolicy.

Chỉ bật sau khi DB đích có schema được thống nhất. Schema guard chỉ SELECT các cột cần thiết và dừng khởi động nếu thiếu; không chạy DDL, không tự sửa DB.
Chuỗi mới ưu tiên develop: `1001` là lịch nhận kho, ledger/outbox dùng `db.migration.V2026101103__ensure_rental_ledger_and_notification_outbox`. DB đã áp dụng SQL ledger `1001` vẫn resolve bản bất biến tại `db/migration-history/legacy-1001` qua `DevelopMigrationHistoryLocations`; không sửa history/checksum hoặc chạy lại bảng. `1103` chỉ tái sử dụng schema cũ có đúng history và fingerprint, cấu trúc lệch/bảng không có nguồn gốc bị chặn. Không scan toàn bộ history root. Xem [báo cáo migration hiện tại](DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md). Chưa rollout DB chung; deployment cần backup và kiểm chứng DB đích.
SQL `rental-ledger-test-schema.sql` và `notification-outbox-test-schema.sql` trong test resources chỉ dành cho H2 test cô lập, không phải migration MySQL.
Không đổi cấu hình `ddl-auto` của ứng dụng, role/grant, Payment, Booking, Return hoặc writer capacity.
Khi communication tắt, Support giữ notification trực tiếp hiện có. Khi bật nhưng chưa có close policy hợp lệ, resolve vẫn dùng notification trực tiếp; không tạo bằng chứng nhận giả.
Không thêm endpoint thu/chuyển tiền. Các hàm ghi ledger là persistence nội bộ; không được gọi từ request chưa xác minh.

## 2. Đọc sổ tài chính

| Method / path | Quyền bắt buộc |
|---|---|
| GET `/api/customer/rentals/{id}/ledger` | Customer đang ACTIVE, role hiện hành trong token và DB, Rental thuộc chính Customer |
| GET `/api/manager/rentals/{id}/ledger` | Manager hiện hành, `rentals:read`, `payments:read`, scope READ thực tại cơ sở |
| GET `/api/business/rentals/{id}/ledger` | Business hiện hành, `rentals:read`, `payments:read` |

Bearer bắt buộc. Response dùng envelope `ApiResponse`, `data` gồm:

| Trường | Kiểu / ý nghĩa |
|---|---|
| rentalId | UUID của Rental đã được kiểm tra quyền |
| completeness | UNKNOWN nếu chưa có account; PARTIAL nếu có các dòng đã ghi nhận |
| currency | VND, không tự chuyển ngoại tệ |
| revision | Long hoặc null khi UNKNOWN |
| obligations | null khi UNKNOWN; danh sách khoản đã ghi nhận khi PARTIAL |
| receipts / refunds | null khi UNKNOWN; danh sách đã ghi nhận khi PARTIAL |
| reason | Giải thích dữ liệu chưa được xác minh đầy đủ |

Khoản phải thu có `id, renewalId, kind, amount, dueAt, outstanding`.
Phiếu thu có `id, method, amount, receivedAt, simulated`.
Hoàn tiền có `id, amount, status`.
Không đưa sourceRef, evidenceRef, file UUID hoặc actor nội bộ vào DTO đọc của khách hàng.
`UNKNOWN` khác danh sách rỗng đã biết; `PARTIAL` khác xác nhận không còn nợ. Không dựa vào query này để bật approval/completion.

### Persistence nội bộ và giới hạn tiền

`LedgerStore` gồm `open, charge, receive, reserveRefund, executeRefund, read`.
Mọi lệnh ghi cần transaction thực, không read-only; account khóa `FOR UPDATE`, revision phải khớp.
Idempotency có phạm vi actor + operation + key; cùng key/payload trả kết quả cũ, đổi resource/payload trả conflict. Key và payload được băm trước lưu.
Caller chịu trách nhiệm kiểm tra role/scope, resource, nguồn giá được chấp nhận, tính hợp lệ của chứng từ/file/gateway và deadline. Một UUID không chứng minh file hoặc tiền thật.

- Số tiền nguyên VND, không âm, tối đa 18 chữ số; khoản phải thu cho phép 0, phiếu thu/hoàn tiền phải lớn hơn 0.
- Khoản phải thu định danh theo rental + sourceRef + kind; không ghi đè giá khoản đã tạo.
- Allocation bắt buộc cùng Rental, tổng đúng bằng phiếu thu, tiền thật không vượt số dư chưa thanh toán.
- CASH và VERIFIED_BANK chỉ được caller dùng sau xác minh thu tiền thật. SIMULATED không giảm nợ, không tăng cọc thật, không được hoàn.
- Một evidenceRef chỉ được ghi một phiếu thu; một payoutRef chỉ được ghi một lần chi.
- RESERVED chỉ giữ hạn mức hoàn, không phải đã chuyển tiền. EXECUTED cần chứng cứ chi thực từ executor.
- Số tiền giữ và đã hoàn không vượt allocation đã thu thật; lệnh lỗi rollback cùng allocation và idempotency receipt.
- Hiện executeRefund là **đảo phân bổ tiền đã thu**: khoản phải thu tương ứng tăng lại. Đây không phải engine hủy/miễn khoản phải thu hoặc quyết toán trả cọc. Chưa có credit-note/waiver/entitlement policy hoặc bộ phận chuyển khoản; không nối P13 chỉ bằng hàm này.
- Thời điểm due/receipt lưu epoch milliseconds; đọc lại chuẩn hóa ở độ chính xác này. Không dùng thời điểm Client làm nguồn nghiệp vụ.

### Provider P01/P06/P14

`LedgerFinancialAdapters` đọc các dòng chính thức chỉ khi có `LedgerCoverageSource` chứng nhận đúng Rental, tuple customer/facility, revision, billing mode và thời điểm kiểm tra.
Hiện **chưa có implementation runtime của LedgerCoverageSource**. Tạo account/dòng tiền hoặc import một phần dữ liệu không tự chứng minh coverage.
Coverage phải truy xuất đủ các nghĩa vụ lịch sử, khoản cọc đang giữ, allocation, refund và dispute từ tất cả writer liên quan.
P01 giữ securityDeposit null khi chưa chứng nhận phần cọc; không báo COMPLETE vì các khoản khác đã đủ.
P06 cần disputesCovered và protocol consistency-through-approval của provider; mặc định consistency=false. Chưa mở khóa approval.
P14 không suy obligation từ ngày hết thuê hoặc một Payment.status.
Kernel ledger chưa triển khai P11: còn thiếu attempt/reconciliation, statement có version/expiry, accepted-term binding, CASH evidence và required-obligation set.

## 3. Policy thông báo và hỗ trợ

GET/PUT `/api/business/facilities/{id}/communication-policy`.
Bearer bắt buộc. PUT chỉ Business ACTIVE có `policies:read` và `policies:update` trong token và DB.
GET service có thể kiểm Manager có `policies:read` và scope READ; contract này không cấp quyền chung hoặc đổi shared security cho route Business.
Storage dùng `SystemSetting` hiện có, key riêng `duongCommunicationPolicy:{facilityId}`; không seed mặc định.

| Body PUT | Ràng buộc |
|---|---|
| expectedRevision | Long bắt buộc >=0; 0 khi công bố lần đầu; sai trả 409 |
| effectiveFrom | LocalDate bắt buộc |
| effectiveTo | null hoặc >= effectiveFrom, hai đầu bao gồm |
| reminderCooldownMinutes | null nếu chưa có policy nhắc; số nguyên 1..525600 nếu có |
| supportReviewDays | null nếu chưa có policy review; số nguyên 7..365 nếu có |
| customerMayClose | phải cùng có hoặc cùng null với supportReviewDays |

Trường lạ bị từ chối. Policy JSON phải vừa giới hạn 2000 ký tự của setting hiện hữu.
Server tạo facilityId, revision, version, publishedBy, publishedAt; Client không tự cấp metadata.
Khóa Facility/setting và CAS revision; snapshot chỉ được đọc trong thời gian hiệu lực theo Asia/Ho_Chi_Minh.
Không tự điền cooldown, bật customerMayClose hay auto-close khi policy thiếu.
Auto-close deadline = resolvedAt + số ngày lịch review; không tự áp lịch ngày làm việc/ngày nghỉ.
Không bật scheduler auto-close mới; giữ service guard hiện hữu.

## 4. Outbox và biên nhận trong ứng dụng

`NotificationOutbox` có loại OVERDUE và SUPPORT_REVIEW.
P16 enqueue tham gia cùng transaction với caller D4, có key/resource/payload binding và policy version; caller vẫn kiểm cooldown và quyền.
Support resolve dùng cùng resolvedAt cho state và sự kiện RESOLVED; queue bind ticket, customer, event ID, assignment revision và policy hiện hành.
Lỗi resolve rollback queue cùng dữ liệu JPA; không gửi notification ngoài transaction rồi coi là đã thành công.

Trạng thái:

`PENDING` → worker ghi notification thực → `INBOX` → Customer xác nhận nhận → `ACKNOWLEDGED`.

- PENDING chỉ là đã xếp hàng.
- INBOX chỉ là đã ghi thông báo vào DB, chưa chứng minh khách hàng nhận; `isRead` cũ không được nâng thành delivery proof.
- Worker mỗi job transaction REQUIRES_NEW, khóa queue; notification JPA và queue JDBC dùng cùng datasource/transaction. Retry không tạo notification trùng.
- Recipient phải tồn tại và ACTIVE. Lỗi rollback và lưu số lần thử/thời điểm thử lại, không lưu lỗi thô/private payload vào receipt.
- Worker mặc định delay ban đầu 60 giây, kiểm lại mỗi 30 giây, tối đa 50 job/batch. Retry bắt đầu 30 giây, exponential, trần 3600 giây.
- Module tắt không đăng ký worker. Không gửi email/gateway mạng trong transaction này.

POST `/api/customer/notifications/{id}/acknowledgement`.
Body không có; Bearer bắt buộc. Chỉ Customer ACTIVE thực, đúng Notification.user và tracked outbox mới được xác nhận.
Response `data: {notificationId, status: "ACKNOWLEDGED", receivedAt}`.
Gửi lại trả cùng receivedAt, không đổi dấu thời gian ban đầu. Thời điểm do server tạo, lưu ISO-8601 để không mất phần sub-millisecond.
Notification không thuộc khách hàng, không được tracked hoặc không tồn tại: 404. Thiếu/xung đột trạng thái: 409.
Biên nhận này chỉ xác nhận **nhận thông báo trong ứng dụng**, không đồng ý nội dung xử lý, không đóng ticket, không phải email delivered.

P18 chỉ trả ReviewNotice sau ACKNOWLEDGED, đúng current RESOLVED event/customer/policy/version và thời điểm không tương lai.
Chính sách hoặc chu kỳ resolution đổi thì receipt cũ không chứng minh chu kỳ mới; không tự reuse/migrate receipt.
Auto-close vẫn kiểm review deadline, objective/result, escalation và delivery proof theo service hiện hữu.
FE đã có wiring đọc trạng thái acknowledgement và nút Customer gửi xác nhận qua API; không tự coi thao tác đọc/thông báo isRead là bằng chứng nhận. Chưa có bằng chứng browser E2E hoặc toàn bộ khách hàng production đã nhận thông báo.

## 5. Schema cần thống nhất trước rollout

Không đặt SQL test vào production migration. Chưa áp dụng các bảng sau lên MySQL/TiDB chung.

| Bảng riêng | Trường chính / ràng buộc |
|---|---|
| rental_ledger_accounts | rental_id PK, customer_id, facility_id, revision >=0; FK binary projection tới rentals/users/facilities |
| rental_ledger_obligations | id PK, rental_id FK, renewal_id nullable, kind, amount DECIMAL(19,0), due_at_ms, source_ref; unique rental/source/kind; index rental/due; FK binary renewal projection |
| rental_ledger_receipts | id PK, rental_id FK, method, amount, evidence_ref unique, evidence_file_id, actor_id, received_at_ms; index rental/received; FK binary file/actor projections |
| rental_ledger_allocations | receipt_id + obligation_id PK, rental_id, amount; composite FK bảo đảm receipt/obligation cùng Rental; index obligation |
| rental_ledger_refunds | id PK, rental_id, receipt_id, obligation_id, amount, status, decision_ref unique, payout_ref unique nullable; FK allocation; index receipt/obligation/status |
| rental_ledger_commands | actor_id + operation + key_hash PK, rental_id FK, payload_hash, result_id; FK binary actor projection |
| notification_outbox | id PK, recipient_id, resource_id, kind, resolution_event_id nullable, policy_ref/version, content, key_hash/payload_hash, status, attempts, retry_at_ms, notification_id unique nullable, acknowledged_at nullable; FK binary recipient/notification projections |

UUID trong H2 test schema là VARCHAR(36). Migration MySQL dùng CHAR(36) ascii_bin tương thích JDBC hiện tại, kèm cột BINARY(16) generated/stored bằng UNHEX(REPLACE(...)) để tạo FK tới bảng chung mà không ALTER kiểu UUID của bảng chung.
FK kiểm sự tồn tại, không chứng nhận đúng tuple customer/facility/Rental hoặc coverage lịch sử; service/caller vẫn phải kiểm ownership, tuple và chứng từ. Resource/event outbox là tham chiếu đa hình nên không tạo FK giả tới một bảng duy nhất.
Chỉ đã kiểm chứng MySQL 8.4 trên DB test mới, chưa chứng nhận TiDB hoặc DB chung. SQL ledger đã áp dụng storagehub_local test ngày 10/10/2026, không phải rollout shared. Không seed/balance backfill hoặc grant quyền trong migration này.
Outbox unique(resource_id,kind,key_hash), index(status,retry_at_ms,id), index(resource,kind,resolution_event,status).
Guard cột hiện tại không thay kiểm chứng đầy đủ FK/index/engine/transaction isolation trên MySQL/TiDB.

## 6. Lỗi và điều kiện còn thiếu

### API đọc đối chiếu BO bổ sung 10/10/2026

GET `/api/business/facilities/{id}/communication-policy/stored`, query `revision` tùy chọn (số nguyên >=1).
Chỉ BO ACTIVE có `policies:read` thực trong token và DB; communication vẫn phải được bật, nếu tắt trả 404.
Response giữ nguyên DTO policy, trả snapshot đã lưu kể cả future/expired, không áp dụng bộ lọc ngày hiệu lực cho màn hình quản trị.
Khi có revision, đọc đúng lần công bố từ snapshot hiện tại hoặc ActivityLog `COMMUNICATION_POLICY_PUBLISHED` của cùng SystemSetting/cơ sở, xác minh publisher của audit. Thiếu proof: 404; dữ liệu mơ hồ/lỗi: 409.
GET cũ `/communication-policy`, `read(facility, now)`, nhắc hạn, review notice và auto-close vẫn chỉ dùng chính sách có hiệu lực. API mới không được dùng làm nguồn policy của consumer vận hành.
Không thêm writer, sửa transaction publication, schema, migration, role/grant hay nguồn dữ liệu fallback.

### Đọc khả năng xác nhận thông báo (bổ sung 10/10/2026)

GET `/api/customer/notifications/{id}/acknowledgement`, không có body. Response envelope `data`:

```json
{"notificationId":"UUID của thông báo","status":"AVAILABLE","acknowledgementAllowed":true,"receivedAt":null}
```

- `AVAILABLE`: tracking INBOX tồn tại, đúng recipient/notification/resource/type; cho phép gọi POST xác nhận hiện có.
- `ACKNOWLEDGED`: có receipt thật, `acknowledgementAllowed=false`, `receivedAt` là thời điểm đã lưu; mở lại màn hình không cần POST lần nữa.
- `UNTRACKED`: thông báo thuộc Customer nhưng không có outbox tracking (ví dụ trao đổi Support thông thường); `acknowledgementAllowed=false`, `receivedAt=null`.
- Customer phải ACTIVE, đúng role thực trong token/DB và là chủ thông báo. Không thuộc quyền/không tồn tại: 404. Tracking không nhất quán: 409. Module communication OFF: endpoint không khả dụng.
- Chỉ đọc: không thay `isRead`, không tạo receipt/thông báo, không đóng Support. FE không suy quyền xác nhận từ tiêu đề, `isRead` hoặc chỉ `type=SUPPORT`.
- GET lỗi/response không hợp lệ: FE hiển thị chưa xác minh, không tự coi là AVAILABLE/ACKNOWLEDGED. POST hiện có, schema, security chung và NotificationResponse của flow khác giữ nguyên.

### Mã lỗi

400: thiếu/không hợp lệ fields, giới hạn số tiền/references/policy. 401: chưa xác thực.
403: role/grant không đủ hoặc đã bị thu hồi. 404: resource không tồn tại/không thuộc quyền.
409: revision/payload/evidence trùng không khớp hoặc trạng thái/nguồn chưa đủ.

P02 access lifecycle, P05/P07/P10 common capacity/hold writer protocol và P19 objective-specific result chưa được module này triển khai.
P01/P06/P14 vẫn cần writer-backed coverage; P11/P13 mới có primitives, không đủ protocol để công bố port READY.
Không dùng mock, nợ 0, policy mặc định hoặc simulated payment để vượt các giới hạn này.

### Schema Payment bổ sung 11/10/2026

Migration Java opt-in 2026101101 bổ sung payments.gateway_intent_id theo Entity hiện hành, không đổi endpoint/DTO/writer/quyền. Profile rollout dùng db/migration + db/paymentmigration; ledger đã ở location chung, Payment vẫn opt-in. Mã hiện có giữ nguyên; NULL/blank nhận LEGACY-UNVERIFIED + Payment ID, vẫn UNVERIFIED, không chứng minh thu tiền hoặc simulation/financial coverage. Collision/schema bất thường bị chặn, không ghi đè/cắt reference. Chưa chạy Payment migration trên DB chung/storagehub_local. Xem PAYMENT_GATEWAY_MIGRATION.md và báo cáo test/startup riêng.
