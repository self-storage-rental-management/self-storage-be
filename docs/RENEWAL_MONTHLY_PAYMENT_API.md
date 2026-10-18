# Gia hạn: trả từng tháng và trả toàn kỳ

## Quy tắc đã triển khai

- `MONTHLY`: trả trước 20% tháng đầu, khi ký thu 80% tháng đầu; các tháng tiếp theo thu đủ tiền tháng.
- `FULL_TERM`: trả trước 20% tổng tiền gia hạn, khi ký thu toàn bộ phần còn lại.
- Báo giá mới không giảm giá. VND nguyên, khoản trả trước làm tròn HALF_UP.
- `paymentPlan` không gửi trong yêu cầu báo giá mới được hiểu là `FULL_TERM`. Snapshot cũ thiếu trường này vẫn giữ nguyên giá, chiết khấu và số tiền đã chấp nhận; không sửa lịch sử.
- Tiền trả trước là một phần tiền thuê, không tạo thêm tiền cọc bảo đảm.
- Không cần Manager duyệt. Gia hạn hoàn tất theo bước ký hiện có, trả đủ phần phải thu khi ký và đủ chứng từ; các tháng tương lai không chặn hoàn tất.
- Kỳ tháng dùng ngày bắt đầu gia hạn làm mốc. Các ranh giới được tính từ ngày gốc bằng `plusMonths`, tránh lệch ngày cuối tháng. Kỳ sau đến hạn lúc 00:00 ngày bắt đầu kỳ theo `Asia/Ho_Chi_Minh`.
- Có thể trả trước nhiều kỳ hoặc toàn bộ số dư, không giảm giá và không đổi ngày kết thúc hợp đồng. Phải chọn các kỳ chưa trả theo thứ tự; khoản đến hạn cũ không được bỏ qua.

## API

| Phương thức | Path | Nội dung |
|---|---|---|
| GET | `/api/customer/rentals/{rentalId}/renewal-options` | Mỗi gói bổ sung `paymentPlans` |
| POST | `/api/customer/rentals/{rentalId}/renewal-quote` | Body `pricingPackageCode`, `paymentPlan`; trả lịch dự kiến và `dueAtSigning` |
| POST | `/api/customer/rentals/{rentalId}/renewal-requests` | Nhận báo giá đã lưu theo luồng hiện có |
| POST | `/api/customer/renewals/{id}/payment-intents` | Giữ API thu khoản trả trước |
| GET | `/api/{role}/renewals/{id}/payment-schedule` | `role`: customer, staff, manager |
| POST | `/api/{role}/renewals/{id}/installment-payment-quotes` | `role`: customer hoặc staff; chọn `installmentIds` |
| POST | `/api/customer/renewals/{id}/installment-payment-intents` | Body `paymentQuoteId`, header `Idempotency-Key` |
| POST | `/api/staff/renewals/{id}/installment-receipts` | Thu tiền mặt/chuyển khoản có chứng từ |
| GET | `/api/{role}/renewals/{id}/payments/{paymentId}` | Xem trạng thái một giao dịch; ba vai trò trên |
| GET | `/api/{role}/renewals/{id}/payments/{paymentId}/receipt` | Biên lai đã đối soát; ba vai trò trên |
| GET | `/api/{role}/renewals/{id}/payments` | Timeline hiện có bổ sung `INSTALLMENT_PAYMENT` |

Ví dụ báo giá gia hạn:

```json
{"pricingPackageCode":"6_MONTHS","paymentPlan":"MONTHLY"}
```

Ví dụ chọn kỳ sau khi gia hạn hoàn tất ký:

```json
{"installmentIds":["uuid-ky-2","uuid-ky-3"]}
```

Báo giá thanh toán có hiệu lực 15 phút, gắn người tạo, phiên bản điều khoản, phiên bản sổ công nợ và phân bổ cụ thể. Không nhận số tiền do FE tự tính. Nếu sổ công nợ thay đổi phải lấy báo giá mới. Một báo giá chỉ gắn một giao dịch. Giao dịch đã thất bại cần báo giá mới để thanh toán lại; mất kết nối dùng lại khóa cũ.

Ví dụ ghi nhận tại cơ sở:

```json
{
  "paymentQuoteId":"uuid-bao-gia",
  "transactionReference":"so-bien-nhan",
  "paymentMethod":"BANK_TRANSFER",
  "receiptFileId":"uuid-tep",
  "received":true
}
```

Header `Idempotency-Key` bắt buộc. `paymentMethod` là `CASH` hoặc `BANK_TRANSFER`. Staff tạo báo giá bằng tài khoản của mình, upload qua cơ chế tệp hiện có với `DUONG_RENEWAL_INSTALLMENT_RECEIPT`, gắn đúng renewal và người upload. Staff cần scope OPERATE và quyền xem hồ sơ/thu tiền. Manager chỉ xem, không ghi nhận thu tiền bằng endpoint này.

## Trạng thái và phân bổ

- Lịch dự kiến trước khi mở thanh toán: `PLANNED`, chưa phát sinh nợ; hạn kỳ đầu chưa cố định.
- Lịch sau khi mở thanh toán: `UNPAID`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `CANCELLED`.
- `paidAmount` lấy từ khoản đã phân bổ trừ hoàn tiền; khoản được hủy công nợ không bị hiểu nhầm là tiền đã thu.
- Ledger lưu phần trả trước, phần còn lại tháng đầu và từng tháng sau thành các nghĩa vụ có nguồn/phiên bản riêng. API nhóm hai khoản đầu thành một kỳ tháng đầu.
- Không phát sinh một hợp đồng mới khi khách trả sớm. Các giao dịch kỳ sau không chuẩn bị lại lịch ký và không đổi trạng thái `completed` của gia hạn.

## Happy path và ngoại lệ

- HP01/HP02: chọn hình thức, nhận báo giá, chấp nhận, chọn lịch ký, trả trước, thu đủ khoản khi ký, lưu hợp đồng và hoàn tất.
- AP01/AP02: chọn một hoặc nhiều kỳ chưa trả, lấy báo giá rồi trả qua VNPay hoặc tại cơ sở; tiền phân bổ từng kỳ.
- EP05–EP11: khóa yêu cầu và liên kết báo giá chống trùng; kiểm tra chữ ký, merchant, nguồn giao dịch và số tiền như luồng hiện có. Tiền đã xác minh được lưu trước, phân bổ được thử lại bằng worker hiện có.
- VNPay thất bại/hết hạn ở kỳ sau chỉ đóng lần thanh toán đó; không đóng hợp đồng gia hạn đã ký. Kết quả chưa rõ vẫn chờ đối soát, không cho thu trùng.
- Nếu hồ sơ chuyển trả kho/thu hồi trước callback, tiền nhận được giữ chờ đối soát; không tự khôi phục thuê hoặc phân bổ vào khoản đã quyết toán.
- Khi ký chỉ kiểm tra khoản ban đầu; các khoản tháng tương lai không bị buộc trả hết.
- Khi yêu cầu chưa ký bị hủy/hết hạn, các khoản chưa thu được hủy theo cơ chế credit hiện có, giải phóng lịch/chỗ giữ. Hủy sau khi thu tiền vẫn theo quy trình đối soát/hoàn tiền hiện có.
- Khi ký thất bại, xử lý tiền trả trước theo chính sách hiện có; các khoản tháng chưa hưởng được hủy riêng từng nghĩa vụ để không tạo nợ sai.

## Nhắc hạn và quá hạn

- Tác vụ nhắc hạn: trước hạn tối đa 3 ngày, ngày đến hạn và sau quá hạn; lưu khóa từng kỳ/loại thông báo để không gửi lặp.
- Khách nhận cả ba loại; Staff có quyền thu tiền và Manager thuộc cơ sở nhận thông báo đến hạn/quá hạn. Nội dung hệ thống bằng tiếng Việt.
- Kỳ tương lai chưa đến hạn không bị tính quá hạn. Sổ công nợ theo tháng được đưa vào nguồn quá hạn dùng chung khi đủ chứng cứ writer/receipt; dữ liệu thiếu bằng chứng tiếp tục giữ trạng thái chưa xác thực.
- Chưa bổ sung một mốc tự động thu hồi hoặc mất tiền trả trước mới do nợ tháng. Nhóm chưa chốt ngưỡng này; các mốc/chính sách thu hồi và quyết toán đang có vẫn là nguồn quyết định.
- Trả kho sớm tiếp tục theo luồng quyết toán hiện có; không tự hoàn tiền hoặc xóa tất cả kỳ tương lai chỉ vì tạo hồ sơ trả kho.

## Triển khai

- Migration mới: `V2026101901__renewal_installment_payment_quotes.sql`, bổ sung bảng báo giá thanh toán, liên kết giao dịch và khóa thông báo. Không sửa/xóa snapshot hoặc migration lịch sử.
- Dùng cấu hình ledger/renewal obligations/sandbox reconciliation/payment lifecycle hiện có; API trả lỗi rõ nếu chưa bật thành phần cần thiết.
- Không chứa thông tin merchant trong FE hoặc API response. Callback/IPN VNPay giữ nguyên paths.
- FE chưa được thay đổi trong đợt này. Cần nối lựa chọn hình thức và các API mới vào FE trước khi khách dùng giao diện.
- Chưa tự áp dụng migration lên database local và chưa tự commit/push.
