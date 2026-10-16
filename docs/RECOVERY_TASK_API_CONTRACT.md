# Recovery task operations

Cập nhật 15/10/2026. Gia hạn không yêu cầu Manager duyệt. Đến `recoveryStart` ngày quá hạn thứ 8, nếu gia hạn chưa hoàn tất thì cọc gia hạn được ghi nhận `FORFEITED`. Cọc phải có Payment/receipt/allocation đã xác thực; đây là xử lý tiền đã thu, không tạo thêm khoản phải thu và không ảnh hưởng tiền bảo đảm Rental.

## Ghi nhận mất cọc

`RenewalDepositSettlementCoordinator` sử dụng giờ server và mốc thu hồi từ `OverdueSources.TermSource`. Hết hạn ký 72 giờ trước ngày thứ 8 chưa làm mất cọc. API đọc trả `AWAITING_RECOVERY_DEADLINE` khi hạn ký đã qua mà chưa có disposition.

`LocalRenewalAutoRecoveryService` gọi cùng cơ chế đối soát trong transaction bàn giao. Receipt, obligation, accepted revision, số tiền và môi trường phải khớp. Thiếu proof hoặc còn Payment chưa đối soát thì hoãn/rollback. Lưu bản ghi duy nhất vào `renewal_deposit_forfeitures`, event `DEPOSIT_DISPOSITION`, audit và thông báo Customer; giữ nguyên Payment/receipt/allocation lịch sử. Phần tiền thuê gia hạn chưa thu được hủy bằng cancellation credit hiện có, không xóa lịch sử.

Renewal completed được loại trừ. Refund đã đặt quyền hoặc chi trả không bị đảo tự động. Cọc đã forfeited bị chặn tại cả API hoàn tiền gia hạn và Ledger refund reservation. Chính sách hiện tại bắt đầu thu hồi lúc 08:00 ngày thứ 8 theo timezone cơ sở; client không truyền thời gian hoặc số tiền cho tác vụ tự động.

## API và quyền

Tiền tố: `/api/{role:staff|manager}/recovery-tasks/{id}`.

| Method | Path | Chức năng |
|---|---|---|
| GET | `/operations` | Tiến trình, expectedVersion, ReturnCase liên kết và ID chứng từ |
| POST | `/start` | OPEN → IN_PROGRESS |
| POST | `/inspection` | Kiểm kê và tạo hồ sơ quyết toán ReturnCase từ Recovery |
| POST | `/settlement-receipts` | Ghi chứng từ thực thu/hoàn tiền theo số dư quyết toán |
| POST | `/completion` | Đóng Recovery, kết thúc Rental và giải phóng kho khi đủ điều kiện |

API danh sách/chi tiết task hiện có giữ nguyên. POST cần `Idempotency-Key` 1–100 ký tự và `expectedVersion`. Ban đầu version=0; mỗi lệnh thành công tăng một. Replay kiểm tra lại quyền trước khi trả kết quả cũ. Cùng key dùng lại với task/operation/body khác trả 409. Sai phiên bản cũng trả 409, cần tải lại dữ liệu.

API Return danh sách/chi tiết và kết quả thao tác bổ sung các trường nguồn sau khi kiểm tra quyền xem ReturnCase hiện có:

- `sourceType`: `CUSTOMER_RETURN` hoặc `RECOVERY`.
- `recoveryTaskId`, `recoveryTaskStatus` (`OPEN`, `IN_PROGRESS`, `CLOSED`), `recoveryCompletedAt`: null với trả kho thường; lấy từ liên kết chính thức với task khi thuộc Recovery.

FE dùng các trường này để định tuyến thu/hoàn tiền sang API chứng từ Recovery. `status=completed` của ReturnCase chỉ là hoàn tất quyết toán; chỉ task `CLOSED` kèm thời điểm hoàn tất mới hiển thị đã thu hồi xong. Không suy luận nguồn Recovery từ ghi chú hoặc tên hồ sơ.

Đọc cần role và grant `rentals:read` hiện hành, scope READ tại cơ sở. Ghi cần thêm `returns:process` và scope OPERATE. Ghi chứng từ quyết toán cần thêm `payments:collect`. Token và quyền/scope hiện hành trong DB đều được kiểm tra. Manager được xử lý trong phạm vi được cấp; không có bước duyệt yêu cầu gia hạn.

### POST /start

```json
{"expectedVersion":0,"note":"Tiếp nhận nhiệm vụ kiểm kê thu hồi"}
```

### POST /inspection

```json
{
  "expectedVersion":1,
  "inspection":{
    "inventoryMatch":"match",
    "damageClassification":"no_damage",
    "damageFee":0,
    "cleaningFee":0,
    "lostItemFee":0,
    "overdueFee":0,
    "outstandingFee":0,
    "staffNotes":"Đã lập biên bản kiểm kê",
    "returnedKey":true,
    "returnedCard":true,
    "returnedLock":true,
    "proposedUnitStatus":"available"
  },
  "evidenceFileIds":["UUID-file-kiem-ke"]
}
```

Phí VND nguyên không âm; tổng phí tối đa 999999999999. Trạng thái kho đề xuất chỉ available hoặc maintenance. Không nhận URL ảnh ngoài; dùng 1–10 FileAsset ID kiểm kê gắn đúng task. ReturnCase chỉ tạo một lần, `requestedBy` là nhân viên thực hiện; không giả Customer yêu cầu trả kho. Quan hệ nguồn được lưu chính thức bằng `recovery_task_operations.return_case_id` duy nhất, kèm ghi chú `OVERDUE_REPOSSESSION` trong hồ sơ.

Tái sử dụng nghiệm thu, tính quyết toán và chính sách tiền bảo đảm của `ReturnSettlementService`. Customer vẫn dùng API Return xác nhận/khiếu nại quyết toán; Manager xử lý tranh chấp bằng API hiện có. Đây là xác nhận số tiền quyết toán, không phải duyệt gia hạn. Rental chuyển return_inspection, kho vẫn occupied.

### POST /settlement-receipts

```json
{
  "expectedVersion":2,
  "paymentMethod":"BANK_TRANSFER",
  "transactionReference":"RECOVERY-BANK-20261018-001",
  "receiptFileId":"UUID-file-bien-nhan",
  "confirmed":true
}
```

Phương thức CASH hoặc BANK_TRANSFER. Đây là nhân viên xác nhận đã thực thu/hoàn tiền kèm chứng từ, chưa có xác thực tự động từ ngân hàng. Server lấy số tiền và hướng COLLECTION/REFUND từ ReturnCase payment_due/refund_pending sau xác nhận hoặc giải quyết tranh chấp. Client không truyền amount. Reference/file không được dùng lại. Chứng từ bất biến được lưu vào `recovery_settlement_receipts`; tài chính quyết toán tiếp tục thuộc ReturnCase, không tạo lại nghĩa vụ/receipt của Ledger Renewal.

Sau ghi nhận, ReturnCase completed nhưng kho chưa được nhả. Với ReturnCase liên kết Recovery, API mô phỏng `customer/returns/{id}/pay-settlement` và API cũ `staff/returns/{id}/complete-refund` bị chặn để không bỏ qua chứng từ. Luồng trả kho thường giữ nguyên.

### POST /completion

```json
{
  "expectedVersion":3,
  "assetsCleared":true,
  "unitInspected":true,
  "reportFileId":"UUID-file-bien-ban-hoan-tat",
  "note":"Tài sản đã xử lý xong và gian kho đã được kiểm tra"
}
```

Cần task IN_PROGRESS, kiểm kê đã lưu, ReturnCase completed, biên bản gắn đúng task và xác nhận đã xử lý tài sản/kiểm tra kho. Cùng transaction: lưu người/thời điểm/chứng từ hoàn tất, đóng task, kết thúc Rental và cập nhật kho available/maintenance. Kiểm tra lại Rental, kho occupied, trạng thái đề xuất và không có Rental khác đang chiếm kho. Lỗi bất kỳ bước nào rollback toàn bộ.

Customer xác nhận quyết toán số dư 0 hoặc Staff ghi thu/hoàn tiền không tự giải phóng kho; chỉ `/completion` thực hiện bước vật lý này.

## Chứng từ

Tái sử dụng `POST /api/files` multipart với `file`, `entityId=taskId`, `entityType`:

- DUONG_RECOVERY_INSPECTION
- DUONG_RECOVERY_SETTLEMENT
- DUONG_RECOVERY_COMPLETION

Task phải IN_PROGRESS để upload. Đính kèm kiểm tra file ACTIVE, đúng task/purpose/uploader, đường dẫn nằm trong vùng lưu trữ, kích thước và checksum. Tải qua `GET /api/files/{fileId}` kiểm tra quyền lại. Customer chỉ tải file đã đính kèm thuộc Rental của mình, không đọc file nháp.

## Schema và kích hoạt

Migration bổ sung:

- `db/renewal-deposit-migration/V2026101501__record_renewal_deposit_forfeitures.sql`
- `db/recovery-migration/V2026101502__recovery_task_operations.sql`

Cần các cấu hình rollout hiện có: profile `shared-payment-schema`, các flag `ledger.enabled`, `renewal-simple-payment.enabled`, `recovery-tasks.enabled`, `renewal-auto-recovery.enabled` dưới tiền tố `storagehub.integration`.

Runner `run-rental-support-dev.ps1 -EnableRenewalPayments -EnableAutoRecovery` tải `db/recovery-migration`; bộ định tuyến Flyway bổ sung `db/renewal-deposit-migration` khi bật rollout Payment để không bị bộ lọc migration Java lịch sử loại bỏ. Áp dụng migration theo quy trình database của nhóm khi triển khai; không repair/reset database.

FE tích hợp trên các màn hình hiện có: nhiệm vụ Staff/Manager, trả kho Staff, hồ sơ thuê Customer/Manager và ký gia hạn Staff. Trạng thái cọc `AWAITING_RECOVERY_DEADLINE` hiển thị chờ mốc xử lý ngày thứ 8; `FORFEITED` hiển thị đã ghi nhận mất cọc gia hạn. `renewal-risk` tiếp tục trả trạng thái quá hạn/thu hồi chiếu từ timeline/task; không mở rộng enum RentalStatus dùng chung. Giao diện dùng lại các kiểu input/button hiện có; cập nhật này không thêm migration.
