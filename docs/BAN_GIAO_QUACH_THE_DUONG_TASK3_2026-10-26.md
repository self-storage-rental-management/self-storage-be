# Bàn giao Task 3 — Quách Thế Dương

- Phạm vi: Payment, Deposit, Invoice, Renewal, Date theo 10 yêu cầu và 5 tiêu chí DONE của Task 3.
- Ngày cập nhật: 26/10/2026.
- Nhánh: FE `feature/fe-duong`; BE `feature/be-duong`.
- Tình trạng: đã triển khai các phần bên dưới, đã kiểm tra biên dịch; chưa nghiệm thu đầy đủ các luồng thực tế nên chưa xác nhận DONE toàn bộ.
- Code và tài liệu được bàn giao qua hai nhánh cá nhân; đối chiếu commit thực tế bằng git log và triển khai FE/BE cùng phiên bản.

## Đã làm

1. **Chuẩn hóa payment, reservation, rental và renewal status giữa FE/BE.**
   Dùng contract trạng thái chung sinh từ enum backend; FE kiểm tra mã trạng thái trong phản hồi API. Payment, invoice và workflow có trạng thái riêng, không suy thanh toán thành công từ trạng thái reservation hoặc renewal.
2. **Chặn check-in nếu khoản cọc trả trước chưa thanh toán thành công.**
   Backend kiểm tra Payment của đúng reservation đã PAID, đúng số tiền snapshot, chưa hoàn tiền và có chứng cứ hợp lệ. Kiểm tra khi lên lịch, tiếp tục các bước, thu phần còn lại và bàn giao; không chỉ dựa vào READY_FOR_CHECKIN hoặc ảnh/reference riêng lẻ.
3. **Rà soát luồng thu tiền trong check-in, trước bàn giao.**
   Khoản “cọc” ở điều kiện check-in là tiền thuê trả trước khi đặt kho, khác cọc bảo đảm. Thu phần còn lại theo `dueAtCheckIn = remainingRentalAmount + securityDepositAmount` từ backend, không cộng cọc bảo đảm lần hai. Staff có quyền xác nhận thực thu tiền mặt kèm biên lai, không thêm bước Manager duyệt biên lai.
4. **Setup tính từ bàn giao/check-in thực tế.**
   Dùng timeline bàn giao thực tế; không mặc định lấy ngày đặt hoặc ngày thanh toán. Setup ban đầu hiện tính theo ngày lịch Việt Nam.
5. **Ngày setup miễn phí nằm ngoài kỳ thuê đã thanh toán.**
   Ngày bắt đầu tính tiền thuê lấy sau setup; kỳ thuê được cộng từ mốc bắt đầu tính tiền, không tính setup vào số tháng thuê đã trả.
6. **Bảo toàn snapshot số ngày setup và pricing policy.**
   Quote/reservation lưu giá, số ngày setup và phiên bản chính sách đã chốt. Không lấy catalog/policy hiện tại thay dữ liệu lịch sử. Thiếu snapshot thì hiển thị thiếu dữ liệu và chặn thao tác cần đối soát, không tự điền 0 hoặc suy ngược giá.
7. **Gia hạn không được thêm setup miễn phí.**
   Áp dụng cả chưa quá hạn và overdue. Chưa quá hạn khi hoàn tất thì nối tiếp kỳ cũ; đã quá hạn thì bắt đầu từ ngày hoàn tất ký đủ điều kiện. Không dùng ngày thanh toán làm ngày hiệu lực mặc định.
8. **Xử lý hủy/trả kho trong setup và số tiền hoàn.**
   Sau bàn giao, yêu cầu hủy chuyển vào ReturnCase. Quote mới lưu điều khoản phí chấm dứt bằng một tháng giá thuê đã chốt; khách xác nhận điều khoản và Staff đối chiếu hợp đồng. Staff xác nhận kho trống, thời điểm thực trả và chứng cứ chi phí. Backend tính quyết toán từ tiền thuê thực thu + cọc bảo đảm thực thu − phí một tháng − chi phí/công nợ đã xác minh; không trừ khoản trả trước lần hai. Hoàn thực tế bằng tiền mặt/chuyển khoản ngoài hệ thống rồi ghi nhận chứng từ; không coi duyệt hoàn là đã hoàn tiền.
9. **Hoàn thiện invoice contract/status FE/BE, gỡ invoice giả ở FE.**
   Số tiền và trạng thái bảng kê/giao dịch lấy từ backend. Gỡ mã INV-RNW, ngày phát hành/hạn thanh toán giả và thao tác thu tiền gia hạn cục bộ. Lịch sử không dựng PAID từ event cũ. Dữ liệu legacy giữ để tra cứu, ghi rõ chưa đối soát; bảng kê không thay cho hóa đơn tài chính chính thức.
10. **Kiểm tra startDate, endDate, paid rental period và setupEndDate.**
    Phân biệt ngày đặt, thanh toán, bàn giao, bắt đầu/kết thúc setup và kỳ thuê tính tiền. Dùng `extensionStartDate` thống nhất cho đầu kỳ gia hạn; lưu kỳ hiệu lực riêng với quote và kiểm tra lại khi thao tác qua ngày. Xác nhận nhận kho chỉ dùng Rental có chứng cứ bàn giao, không tự tạo Rental/hợp đồng/ledger theo ngày reservation khi thiếu dữ liệu.

## Đối chiếu tiêu chí DONE

- **Cọc PAID mới được check-in:** đã có chốt backend; còn nghiệm thu payment hợp lệ và các trường hợp chưa PAID/sai số tiền/thiếu chứng cứ.
- **Setup không làm lệch ngày bắt đầu tính tiền thuê:** đã dùng timeline bàn giao và bắt đầu thuê sau setup; còn kiểm tra thực tế setup bằng 0, qua ngày và gần nửa đêm.
- **Renewal không nhận thêm setup:** đã triển khai; còn nghiệm thu cả gia hạn thường và overdue.
- **Invoice/payment status cùng contract FE/BE:** đã chuẩn hóa, tách trạng thái và gỡ invoice gia hạn giả; còn nghiệm thu hiển thị/giao dịch giữa các vai trò.
- **Chính sách không làm thay đổi lịch sử reservation:** đã dùng snapshot đã chấp nhận; còn kiểm tra thay policy/catalog sau đặt kho và hồ sơ cũ thiếu snapshot.

Các tiêu chí trên đã có phần triển khai, chưa được đánh dấu nghiệm thu pass chỉ dựa vào compile/typecheck hoặc HTTP 200.

## Còn thiếu hoặc cần lưu ý

- Chưa chạy unit/integration/E2E hoặc toàn bộ luồng DB/gateway/hoàn tiền thực tế cho bản sửa này.
- Cần nghiệm thu đủ 5 tiêu chí DONE và ghi kết quả vào tài liệu happy path.
- Cần cập nhật mẫu hợp đồng và công bố điều khoản phí trả kho trong setup trước khi dùng với đơn mới.
- Hồ sơ lịch sử thiếu snapshot/chứng cứ hoặc từng hoàn một phần cần đối soát riêng; chưa có công cụ tự khôi phục dữ liệu và tự quyết toán các trường hợp đó.
- Trả thực tế trước `setupEndsAt` mới áp quyết toán setup; nếu nghiệm thu sau setup thì dùng quyết toán kỳ thuê đã bắt đầu.
- Setup lifecycle, task kiểm kê và credential access nhập từ nhánh Trâm qua develop; chỉ phối hợp kiểm tra timeline liên quan Task 3, không ghi nhận là phần Dương tự triển khai.
- Bảng kê/biên lai đã có nguồn backend; phát hành hóa đơn tài chính chính thức và API chuyển khoản hoàn tự động không nằm trong phần triển khai này.
- Bản bàn giao và kịch bản nghiệm thu được sao chép vào docs của repo BE để theo dõi bằng Git; bản docs cấp workspace dùng khi làm việc local.

## File FE đã sửa

Đường dẫn tính từ `self-storage-fe`. Chỉ liệt kê các file chính liên quan Task 3.

- Contract trạng thái: `scripts/generate-status-contracts.mjs`, `src/types/statusContracts.ts`, `src/types/storageHub.ts`, `src/types/rentalApi.ts`, `src/types/renewalApi.ts`, `src/types/renewalStatus.ts`, `src/types/renewalOperationsApi.ts`, `package.json`.
- Kiểm tra phản hồi và thanh toán/check-in: `src/services/statusContractValidation.ts`, `customerReservationApi.ts`, `customerRentalApi.ts`, `staffReservationApi.ts`, `unitAssignmentApi.ts`, `checkInApi.ts`, `rentalApi.ts`, `rentalBookingEvidence.ts`, `renewalApi.ts`, `renewalOperationsApi.ts` trong `src/services/`.
- Snapshot: `src/services/reservationSnapshot.ts`, `src/views/customer/CustomerReservationCard.tsx`, `CustomerReservationPayment.tsx`, `ReservationQuoteSummary.tsx`.
- Gỡ tài chính/invoice legacy: `src/domain/legacyRenewal.ts`, `src/domain/reservationFlow.ts`, `src/store/StorageHubContext.tsx`.
- Bảng kê, trạng thái và mốc ngày: `src/views/rental-api/RenewalDetail.tsx`, `RenewalStatusPanel.tsx`, `LegacyRenewalPaymentRecord.tsx`, `RenewalOperationsPanel.tsx`; `src/views/customer/CustomerRenewalRequestModal.tsx`; `src/views/staff/StaffCheckInOperationsPanel.tsx`; `src/views/manager/ManagerPaymentsPanel.tsx`.
- Lịch sử thanh toán: `src/services/customerPaymentHistory.ts`, `src/views/customer/CustomerPaymentHistory.tsx`.
- Trả kho/hoàn tiền setup: `src/services/returnApi.ts`, `src/views/return-api/ReturnApiPanel.tsx`, `RecoveryReturnSettlement.tsx`, `src/views/customer/ReturnSettlementReceipt.tsx`, `CustomerApp.tsx`, `src/views/manager/ManagerReturnsPanel.tsx`, `src/views/business/BusinessReturnsPanel.tsx` và các màn hình snapshot/check-in liên quan.

## File BE đã sửa

Đường dẫn tính từ `self-storage-be/src/main/java/com/storagehub/`, trừ migration và file test.

- Contract/DTO: `api/StatusContract.java`, `api/renewal/RenewalStatusSummary.java`, `RenewalOperationCommands.java`, `RenewalResponse.java`, `api/reservation/ReservationResponse.java`, `ReservationQuoteResponse.java`, `CreateReservationRequest.java`, `api/checkin/CheckInResponse.java`, `SignCheckInContractRequest.java`.
- Quote/snapshot/check-in: `service/ReservationQuoteService.java`, `ReservationSnapshotView.java`, `ReservationCreationService.java`, `CustomerReservationService.java`, `CheckInHandoverService.java`, `BookingDocumentService.java`, `VnpayService.java`.
- Timeline/ngày gia hạn: `service/renewal/RenewalWorkflowService.java`, `service/renewal/operations/RenewalOperationService.java`, `RenewalOperationSources.java`, `service/rental/period/RenewalEffectivePeriod.java`, `RenewalPeriodCompatibility.java`, `RentalPeriodEvidence.java`, `ProjectHandoffRentalPeriodAdapter.java`, `domain/repo/RentalRepository.java`.
- Payment/invoice và ngày tính phí: `service/PaymentProvenance.java`, `service/renewal/RenewalStatusProjection.java`, `RenewalTransactionStatus.java`, `service/ledger/RenewalVnpaySettlementService.java`, `RenewalDepositSettlementService.java`, `RenewalPaymentReconciler.java`, `RenewalOverdueFeeService.java`.
- Quyết toán setup: `service/SetupEarlyExitPolicy.java`, `ReturnSettlementService.java`, `service/ledger/ReturnFinancialReconciliation.java`, `service/FileStorageService.java`.
- Entity snapshot/quyết toán: `domain/model/ReservationQuote.java`, `ReservationPricingSnapshot.java`, `ReturnCase.java`, `Rental.java`.
- DTO trả kho: `api/returns/ReturnCaseResponse.java`, `ReturnInspectionRequest.java`, `StaffCompleteRefundRequest.java`.
- Test liên quan trong `src/test/java/com/storagehub/`: `service/SetupEarlyExitPolicyTests.java`, `ReturnSettlementServiceTests.java`, `ReservationCreationServiceTests.java`, `ReservationQuoteServiceTests.java`, `ReservationSnapshotViewTests.java`. Test đã cập nhật nhưng chưa thực thi.

## API/DTO chính

- API lên lịch/tiếp tục check-in: kiểm tra khoản trả trước PAID đúng reservation, số tiền và chứng cứ; không dựa riêng vào reservation status.
- `POST /api/staff/check-ins/{id}/remaining-payment`: thu đúng bảng kê backend, kiểm tra khoản trả trước và snapshot trước khi ghi nhận khoản còn lại.
- `POST /api/staff/check-ins/{id}/setup/start`: kiểm tra điều kiện thanh toán/snapshot trước bắt đầu setup.
- `POST /api/staff/check-ins/{id}/contract-signing`: thêm `confirmedSetupEarlyExitPolicyVersion` khi snapshot có điều khoản setup mới.
- `GET /api/customer/reservations` và `GET /api/customer/reservations/{id}`: trả dữ liệu snapshot, phiên bản chính sách, trạng thái đầy đủ và cảnh báo; trường thiếu lịch sử có thể null.
- `POST /api/customer/reservations/quote` và `POST /api/customer/reservations`: lưu snapshot; đơn mới cần `acceptedSetupEarlyExitPolicyVersion` khớp quote khi có điều khoản mới.
- `POST /api/customer/reservations/{id}/vnpay/payment-url`: không mở thanh toán khi pricing/setup snapshot thiếu hoặc lệch.
- `POST /api/customer/reservations/{id}/receipt-confirmation`: chỉ xác nhận hồ sơ bàn giao có thật; không tạo Rental/ngày thuê dự phòng.
- `GET /api/staff/renewals/{id}/payable-statement`: bảng kê tiền và ngày hiệu lực từ backend.
- `POST /api/staff/renewals/{id}/completion`: yêu cầu đủ tiền, hợp đồng đã ký và ngày hiệu lực khớp backend; body có `expectedVersion`, `arrivalRef`, `signedDocumentFileId`, `expectedEffectiveStartDate`.
- `POST /api/staff/returns/{id}/inspection`: yêu cầu kho trống `unitVacated=true` đối với trả trong setup; chi phí phát sinh cần ghi chú/chứng cứ.
- `ReturnCaseResponse`: bổ sung `setupExitRequested`, `setupEarlyExit`, `setupReturnedAt`, `setupEarlyExitPolicyVersion`, `rentalRefundCredit`, `earlyTerminationFee`. FE hiển thị số backend, không tự tính hoàn.
- `POST /api/{staff,manager}/returns/{id}/settlement-receipts` và `POST /api/business/returns/{id}/complete-refund`: kiểm tra quyền, số tiền, phương thức, reference và biên lai trước đóng hồ sơ.
- Các command tài chính/hoàn tất dùng Idempotency-Key theo contract hiện có. Đối chiếu controller/OpenAPI để lấy body đầy đủ.

## Database/migration

- Có migration của Task 3: `self-storage-be/src/main/resources/db/migration/V2026102602__setup_early_exit_settlement.sql`.
- Bổ sung điều khoản/phí vào quote và pricing snapshot; trường quyết toán setup vào ReturnCase; dữ liệu bảng kê setup vào snapshot tài chính/lịch sử; mở độ dài mã biên nhận.
- Không áp điều khoản mới để tự tính lại reservation lịch sử hoặc sửa quyết toán đã hoàn tất. Hồ sơ setup cũ còn mở được đánh dấu để đối soát.
- Phụ thuộc migration từ develop: `V2026102601__setup_inventory_tasks_and_credential_access_policy.sql` của phần check-in/setup.
- **Đã áp trên DB local khi khởi động BE ngày 26/10/2026:** log Flyway ghi nhận áp thành công đến `v2026102602`. Điều này thay cho ghi chú “chưa áp migration” trong bản bàn giao trước.
- Môi trường khác vẫn phải kiểm tra Flyway history và áp đủ migration nền; không đổi tên/chạy lại migration đã áp.
- Các thay đổi nullable ở response là contract API, không tự đổi dữ liệu lịch sử thiếu thành 0.

## Phụ thuộc task khác

- Booking/check-in: phối hợp nghiệm thu khoản trả trước PAID, thu phần còn lại trước bàn giao, timeline bàn giao/setup và chuyển ACTIVE từ lifecycle hiện có.
- Payment/ledger: callback đã xác minh, phân bổ khoản thu và chứng từ hoàn phải khớp; phân biệt xác nhận thực thu tiền mặt với ảnh chuyển tiền chưa đối soát.
- Catalog/pricing: quote mới ghi đủ snapshot, không ghi đè chính sách đã chốt trên reservation cũ.
- Leader/người quản lý hợp đồng: đưa điều khoản phí trả kho trong setup vào mẫu hợp đồng và công bố cho khách.
- Phân quyền/file: chứng từ đúng hồ sơ/cơ sở và quyền thu/hoàn hợp lệ.
- Môi trường/QA: đồng bộ FE/BE, migration và dữ liệu kiểm tra; ghi kết quả nghiệm thu 5 tiêu chí DONE.

## Cách test nhanh

Các bước dưới chưa được nghiệm thu pass; không chạy thêm test trong lượt cập nhật tài liệu này.

1. Khởi động FE/BE cùng bản sửa, kiểm tra migration; chuẩn bị Customer và Staff đúng cơ sở.
2. Thử check-in khi payment PENDING/FAILED, sai số tiền, đã hoàn hoặc thiếu chứng cứ: phải bị chặn. PAID đúng khoản và chứng cứ hợp lệ mới được tiếp tục. Kiểm tra cả API trực tiếp với check-in cũ.
3. Staff thu phần còn lại đúng `dueAtCheckIn`, upload biên lai thực thu; không thu thêm cọc bảo đảm ngoài bảng kê và không ghi nhận khoản thu hai lần khi gửi lại.
4. Bàn giao/check-in thực tế: kiểm tra thời điểm bắt đầu setup, kết thúc setup, ngày bắt đầu tính thuê và endDate. Setup bằng 0, bàn giao gần nửa đêm và qua ngày phải giữ đúng kỳ thuê đã thanh toán.
5. Tạo quote/reservation, sau đó đổi giá và số ngày setup của policy/catalog: đơn cũ giữ snapshot. Thiếu snapshot phải báo cần đối soát, không lấy giá mới hoặc mặc định 0.
6. Gia hạn thường: đủ tiền và upload hợp đồng đã ký mới hoàn tất; kỳ mới nối tiếp kỳ cũ, không thêm setup. Gia hạn overdue: ngày hiệu lực là ngày hoàn tất đủ điều kiện, không tự lấy ngày trả trước.
7. Kiểm tra bảng kê/invoice status, payment status và lịch sử giữa FE/BE. Không suy PAID từ event/ảnh/reference; renewal legacy không tự tạo invoice hoặc xác nhận thu tiền.
8. Trả kho trong setup: gửi ReturnCase → kiểm kê/chứng cứ → xác nhận kho trống → bảng kê backend → xác nhận khách → thực hoàn/thu với chứng từ → đóng hồ sơ. Trả thực tế sau setup dùng công thức kỳ thuê đã bắt đầu.
9. Ví dụ đã thu 6 triệu tiền thuê + 2 triệu cọc bảo đảm, phí chấm dứt 2 triệu và hư hại 300 nghìn: hoàn 5,7 triệu. Không trừ khoản trả trước lần hai; sai số tiền/thiếu biên lai không được đóng, gửi lại không hoàn trùng.
10. Ghi kết quả theo 5 tiêu chí DONE ở trên; chỉ xác nhận hoàn tất sau khi nghiệm thu các trường hợp chính và trường hợp bị chặn.

Kịch bản nghiệm thu chi tiết và biểu mẫu kết quả: [KICH_BAN_NGHIEM_THU_TASK3.md](KICH_BAN_NGHIEM_THU_TASK3.md). Tất cả ca đang ở trạng thái Chưa chạy.
