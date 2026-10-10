# Contract tích hợp nguồn gia hạn, quá hạn và file hỗ trợ

Cập nhật 09/10/2026. Đây là API/adapter bổ sung của dự án; không thay Booking, Check-in, Payment, Return, policy config cũ, shared security hoặc schema.
Không tự tạo policy, cấp quyền, phân công, xác nhận tiền hoặc backfill bằng chứng lịch sử.

## 1. Công bố chính sách gia hạn

GET/PUT /api/business/facilities/{facilityId}/renewal-policy.
Bearer token bắt buộc. GET dành cho BO có policies:read hoặc Manager có policies:read và scope READ thực tại cơ sở.
PUT chỉ BO đang ACTIVE, có policies:read và policies:update thực trong DB và token.
Không sửa role/grant mặc định của dự án để vượt điều kiện này.

Body PUT:

| Trường | Ràng buộc / nghĩa |
|---|---|
| expectedRevision | Bắt buộc >=0; lần đầu 0, lần sau revision từ GET. Sai revision trả 409 |
| effectiveFrom / effectiveTo | effectiveFrom bắt buộc; effectiveTo có thể null, không nhỏ hơn effectiveFrom; khoảng ngày bao gồm hai đầu |
| quoteTtlMinutes / paymentWindowHours | Số nguyên bắt buộc >0, không lấy TTL Booking làm mặc định |
| requestWindowDays | Số nguyên bắt buộc >=0 |
| depositRate | Tỷ lệ 0..1 do BO nhập; không tự điền tỷ lệ đã đề xuất trong tài liệu |
| eligiblePackageIds | Tập ID bắt buộc, <=20, không null; package phải tồn tại và thuộc cùng facility. Tập rỗng không tạo package mặc định |
| signing | Có thể null; nếu có, signingWindowMinutes và exceptionExtensionLimitMinutes bắt buộc >0 |
| term | Có thể null; nếu có, phải đủ các trường dưới đây |

term.calendar hiện chỉ hỗ trợ CALENDAR_DAYS; term.timezone chỉ hỗ trợ Asia/Ho_Chi_Minh.
warningThroughDay >0, seriousThroughDay > warningThroughDay, urgentThroughDay > seriousThroughDay,
recoveryFromDay = urgentThroughDay +1. recoveryCutoffTime và recoveryStartTime bắt buộc là giờ địa phương.
Không cung cấp lịch ngày làm việc/ngày nghỉ bằng dữ liệu giả.
Trường lạ ở body/signing/term bị từ chối.

Response ApiResponse.data chứa schema, facilityId, revision, version, publishedBy, publishedAt và toàn bộ snapshot đã công bố.
version do server sinh mới mỗi lần công bố. GET khi chưa công bố trả 404; policy malformed/incomplete trả lỗi, không giả thành policy rỗng.
Adapter trả Optional.empty khi thiếu snapshot, ngoài effective range hoặc thiếu nhánh signing/term cần dùng.

Persistence dùng SystemSetting sẵn có, key rentalRenewalPolicy:{facilityId}, JSON <=2000 ký tự; không migration/seed.
PUT khóa Facility trước rồi setting, CAS revision và ghi audit cùng transaction. Không thay global config/package DTO hoặc writer cũ.
Chưa chứng minh triển khai trên MySQL/TiDB chung; kiểm chứng DB test ghi/đọc trong bộ integration tests H2.

## 2. Mapping adapter policy và ngày

PublishedRenewalPolicyAdapters:

- RenewalSources.PolicySource: snapshot theo facility và canonical extensionStart, ref = system-setting:{id}, version/rate/windows/eligible packages từ snapshot thực.
- RenewalOperationSources.PolicySource: cần ngày có bằng chứng và signing đã công bố; deadline vẫn do consumer hiện có kiểm tra.
- OverdueSources.TermSource: ngày cuối được sử dụng L từ RentalPeriodResolver, không dùng raw end exclusive như ngày cuối; cutoff = L + urgentThroughDay tại recoveryCutoffTime; recoveryStart = L + recoveryFromDay tại recoveryStartTime.

ProjectHandoffRentalPeriodAdapter chỉ đăng ký khi storagehub.integration.rental-period.enabled=true. Mặc định OFF giữ nguyên.
Không tự sửa ngày Booking/Rental lịch sử hoặc bật profile production. Hồ sơ không có proof giữ UNKNOWN.
Nguồn term không thu phí, khóa kho, tạo Recovery hoặc thay số dư.

## 3. Phân công nhân viên gia hạn

POST /api/manager/renewals/{id}/staff-assignment.
Header Idempotency-Key bắt buộc; body assignedStaffId, reason, expectedVersion.
201 ApiResponse.data là Result(state,event); event.kind = STAFF_ASSIGNMENT.

Manager phải có rentals:read, rentals:update và scope MANAGE thực.
Nhân viên được chọn phải ACTIVE, role STAFF, rentals:read, rentals:update và OPERATE tại cơ sở thực.
Không đồng nhất Booking.assignedBy với nhân viên ký. Không dùng checkins:process thay quyền gia hạn.
Renewal phải được duyệt, chưa terminal và chưa ghi nhận Arrival; không phân công lại cùng người chỉ để reset.
Receipt idempotency/resource lock/workflow version hiện có giữ nguyên; retry cùng key/payload đọc lại kết quả, khác payload hoặc stale version trả 409.
Sự kiện lưu renewalId, facilityId, staffId, acceptedQuoteId và workflowRevision.

RenewalAssignmentSource kiểm tra role/grants/scope DB cùng claims, assignment hiện hành theo accepted quote.
READ chỉ requires rentals:read; ARRIVAL/INCIDENT/COMPLETE thêm rentals:update.
CASH cần rentals:read, payments:read, payments:collect; REFUND của Manager thêm payments:read/payments:collect.
Danh sách Staff lấy đúng assignment trước phân trang; sai actor/resource không lộ hồ sơ.
Thiếu assignment / stale quote / hai assignment cùng timestamp không được đoán từ UUID.

Lưu ý triển khai: RoleDataInitializer hiện không cấp rentals:update cho các role mặc định, Staff cũng không mặc định có payments:collect.
Adapter không sửa shared RBAC. Tài khoản thiếu grant thực vẫn bị chặn; tests dùng grants riêng trong DB H2.

## 4. Xác minh lỗi cơ sở

POST /api/manager/renewals/{id}/facility-fault-reviews.
Header Idempotency-Key bắt buộc; body incidentId, facilityFault (boolean bắt buộc), reason, evidenceFileIds (<=10), expectedVersion.
201 Result với event.kind = FAULT_REVIEW; không tự duyệt ngoại lệ, đổi hạn hoặc tạo hoàn tiền.
Incident phải thuộc đúng Renewal. Manager có rentals:read/rentals:update và MANAGE thực.
File phải thuộc purpose FAULT_REVIEW của đúng Renewal, người thực hiện và file thật.
Adapter chỉ chấp nhận kết luận true hiện hành, đúng reviewer và scope/quyền reviewer vẫn hợp lệ.
Kết luận false, thiếu review, reviewer bị revoke hoặc review cùng timestamp mơ hồ đều chặn, không suy từ note incident.

## 5. Upload và đọc bằng chứng

Tái sử dụng endpoint FileController hiện có: POST /api/files (multipart file/entityType/entityId), GET /api/files/{fileId}.
Giữ nguyên giới hạn size, MIME/magic bytes và metadata response của endpoint.
Thêm namespace riêng:

- DUONG_RENEWAL_ARRIVAL, INCIDENT, SIGNED_CONTRACT, EXCEPTION, FAULT_REVIEW, REFUND (tiền tố DUONG_RENEWAL_ áp dụng cho từng purpose).
- DUONG_SUPPORT_PUBLIC, DUONG_SUPPORT_INTERNAL.
- DUONG_SUPPORT_UPLOAD: vùng chờ riêng của Customer, entityId = uploader.userId; không phải ticket ID.

Upload DUONG_SUPPORT_PUBLIC với entityId null được server lưu thành DUONG_SUPPORT_UPLOAD có owner binding.
Customer chỉ đọc được staging của mình. Khi tạo/gửi Support, adapter khóa file và chuyển staging sang PUBLIC gắn ticket trong cùng transaction.
File đã gắn ticket không được chuyển ticket khác; INTERNAL không được chuyển PUBLIC.
Non-null staging owner binding ngăn writer khiếu nại hiện có lấy nhầm ảnh chưa liên kết; không sửa PaymentComplaintService.

File renewal phải gắn renewalId ngay khi upload; quyền/purpose/current assignment được kiểm tra lại khi attach và download.
Customer chỉ đọc signed contract sau COMPLETED có COMPLETION event thực liên kết đúng file.
Support PUBLIC cần đúng Customer/ticket hoặc Staff hiện được phân công hoặc Manager trong scope.
INTERNAL không cho Customer, kể cả nếu uploader trùng Customer.
Kiểm file ACTIVE, path thực nằm trong storage root, kích thước thật và SHA-256 thật; UUID đơn thuần không phải bằng chứng.

File các namespace cũ giữ nguyên handler/guard. SupportSources.EvidenceSource bổ sung default supportsFiles(List<UUID>) = true để tương thích provider cũ.
Adapter mới chỉ chứng nhận file PUBLIC/INTERNAL riêng; lịch sử chứa file ngoài namespace được trả evidenceCompleteness=UNKNOWN và evidenceFileIds=null,
không làm lỗi toàn bộ timeline và không giả download/COMPLETE. Denied hoặc corruption trên file thuộc adapter không bị nuốt thành success.

## 6. Bổ sung 10/10/2026: đọc đối chiếu, không đổi writer

### Chính sách đã lưu

- GET `/api/business/facilities/{id}/renewal-policy/stored` trả snapshot đã lưu, kể cả chưa có hiệu lực hoặc hết hiệu lực.
- Query `revision` không bắt buộc, số nguyên >=1. Khi có, trả đúng lần công bố hiện tại hoặc snapshot `RENEWAL_POLICY_PUBLISHED` trong ActivityLog của cùng SystemSetting/cơ sở; không dùng revision mới hơn thay kết quả của lần cũ.
- Chỉ BO ACTIVE, `policies:read` trong token và DB. Response giữ nguyên DTO PublishedRenewalPolicy, không thêm dữ liệu giả hoặc revision 0.
- Không có lịch sử chứng minh revision yêu cầu: 404. Snapshot lỗi/mơ hồ: 409. FE giữ kết quả chưa xác định, không báo lưu thành công.
- Các reader áp dụng chính sách theo ngày, GET/PUT cũ và điều kiện công bố không đổi.

### Phân công gia hạn hiện tại

GET `/api/manager/renewals/{id}/staff-assignment`, Bearer bắt buộc; Manager ACTIVE với `rentals:read` và scope READ thực trong token/DB.
Không thay POST cùng path, Booking assignment, Check-in hay kiểm quyền xử lý của Staff.

Response `{renewalId, expectedVersion, status, staffId, staffName}`:

| status | Ý nghĩa |
|---|---|
| ASSIGNED | Bằng chứng STAFF_ASSIGNMENT hợp lệ với accepted quote hiện tại; Staff còn ACTIVE/quyền/scope OPERATE thực |
| UNASSIGNED | Có workflow/accepted revision nhưng chưa ghi nhận phân công D3; staffId/staffName null |
| UNAVAILABLE | Thiếu workflow/accepted revision hoặc bằng chứng mơ hồ/không hợp lệ; không giả chưa phân công |
| INELIGIBLE | Có bằng chứng phân công nhưng Staff không còn đủ điều kiện; không chứng nhận quyền ký |

expectedVersion là phiên bản workflow thực hoặc null khi thiếu; FE không dùng projection khác phiên bản để chứng nhận phân công hiện tại.
Đọc không ghi event, không tăng version, không tự sửa phân công hoặc cấp quyền.

## 7. Giới hạn còn giữ nguyên

API mới không mở khóa các guard accounting, hold, service calendar, refund hay delivery.
Simulated-payment vẫn là thử nghiệm, không phải chứng từ thu tiền thật.
Notification persisted/read không được coi là bằng chứng delivery của current resolution cycle.
Từ 10/10/2026 có kernel ledger và outbox/review receipt riêng, mặc định OFF; xem [contract ledger và communication](LEDGER_COMMUNICATION_INTEGRATION_API_CONTRACT.md).
Ledger chưa có writer-backed coverage hoặc engine Accounting/Refund hoàn chỉnh. Outbox chỉ cung cấp proof khi Customer xác nhận nhận đúng notification/current resolution/policy; persistence/readFlag đơn thuần vẫn không phải proof.
Cross-writer hold, trạng thái access/PIN và objective-specific Support result vẫn thiếu implementation.
Không thể suy đủ các trường đó từ Payment.status hoặc ghi chú người dùng. End-to-end D1-D5 chưa được chứng nhận hoàn tất.
