# Đặc tả API xử lý quá hạn — D4

08/10/2026. Đường dẫn gốc `/api/manager/overdue-cases`, JWT Bearer. Truy vấn cần MANAGER + VIEW_RENTALS/READ; lệnh cần MANAGE_RENTALS/MANAGE. Áp dụng phạm vi cơ sở trước khi tổng hợp dữ liệu và phân trang. Không ngầm cho Admin/Business thao tác thay Manager.

## Tham chiếu và dữ liệu tổng hợp

Hồ sơ xử lý là dữ liệu tổng hợp có tham chiếu ổn định, KHÔNG phải thực thể nghiệp vụ mới được tạo:
- `RENTAL_TERM:<canonical Rental UUID>`
- `PAYMENT_DUE:<canonical Rental UUID>:<canonical obligation UUID>`

PAYMENT_DUE lấy từ dueAt/outstanding/phân bổ có thẩm quyền; chỉ xuất hiện khi serverNow>dueAt và outstanding>0. Loại nghĩa vụ chưa đến hạn và số dư đã trả/bằng 0. Không suy nợ định kỳ từ monthlyPrice. Bộ kết nối tài chính phải trả bản ghi liên kết đầy đủ; null là UNKNOWN, giá trị âm/trùng/sai Rental/sai tiền tệ/checkedAt ở tương lai là dữ liệu không nhất quán và trả 409, không tự sửa.

RENTAL_TERM lấy từ lastPermittedDate được chủ nguồn xác minh là ngày cuối còn được phép và các giới hạn cảnh báo/nghiêm trọng/khẩn cấp/thu hồi, lịch và mốc giới hạn theo policy BO. Loại hồ sơ đã có actualReturnedAt/Rental completed nhưng có thể bao gồm return_requested/inspection/closing khi hàng vẫn còn. Thiếu nguồn ngày/policy thì không suy ý nghĩa hoặc bổ sung ngược dữ liệu. Điều kiện Recovery dùng thời điểm bắt đầu thu hồi thực theo policy thời hạn thuê, không phải mọi khoản nợ sang ngày 8 đều đủ điều kiện.

## Đường dẫn API

| Phương thức/đường dẫn | Đặc tả |
|---|---|
| GET base | page0,size20 tối đa 100,facilityId,kind ALL/PAYMENT_DUE/RENTAL_TERM,search không phân biệt hoa thường và không diễn giải ký tự đại diện theo Customer/gian/tham chiếu,sort priority/overdueDays/caseRef asc/desc; mặc định priority desc + caseRef asc |
| GET `/{caseRef}` | Hồ sơ hiện tại trong phạm vi; đã giải quyết trả 409, thiếu nguồn trả 409, ngoài quyền xem trả 404 |
| POST `/{caseRef}/follow-ups` | type NOTE/REMINDER,content dài 1–2000,expectedVersion; Idempotency-Key; 201 ghi điều phối chỉ thêm mới |
| GET `/{caseRef}/follow-ups` | page,size; lịch sử thực bất biến vẫn đọc được sau khi đã giải quyết |
| POST `/{caseRef}/recovery-handoffs` | reason dài 1–2000,expectedVersion; Idempotency-Key; 201 chỉ khi bên nhận Recovery thực, có quyền, đã lưu ACK |

Danh sách dùng data/pagination/correlationId chuẩn, bổ sung `asOf` (thời điểm server), `completeness` COMPLETE/PARTIAL và missingSources. Danh sách rỗng/chưa đầy đủ khi thiếu nghĩa vụ hoặc policy thời hạn KHÔNG ĐƯỢC hiểu là không có nợ. Truy vấn không hỗ trợ/lặp và asOf do client gửi bị từ chối 400. Triển khai hiện tại tổng hợp Rental trong phạm vi và nghĩa vụ từ chủ nguồn trước khi lọc/sắp xếp/phân trang; cần tối ưu truy vấn hàng loạt qua chủ nguồn trước khi triển khai quy mô lớn, giữ đúng tổng số và phạm vi.

Trường hồ sơ gồm Rental/cơ sở/Customer/gian, kind/overdueDays/priority, nghĩa vụ/outstanding/tiền tệ thực (null với TERM), policy/mốc giới hạn/recoveryEligible, followUpVersion. Version chỉ mô tả luồng theo dõi xử lý của module này: không có sự kiện => 0, thêm thành công tăng 1. Đây không phải bằng chứng về phiên bản bản chụp tài chính.

NOTE không thay số dư hoặc gửi thông báo. REMINDER cần policy/outbox của chủ nguồn tham gia giao dịch, người nhận thực suy từ Rental, tham chiếu/phiên bản quy tắc giãn cách nhắc. Khóa Rental tuần tự hóa xử lý giữa các Manager cạnh tranh và hạn chế nhắc lặp qua các key khác nhau. externalRef chứng minh thông báo đã QUEUED, KHÔNG chứng minh gửi thành công; việc gửi/thử lại thuộc chủ nguồn chung.

Bàn giao Recovery cần RENTAL_TERM đủ điều kiện theo policy hiện tại và bên nhận có quyền tham gia giao dịch. Bên nhận phải kiểm tra lại Return/tài sản/mốc giới hạn và chống trùng. Tham chiếu RECEIVED/ALREADY_RECEIVED thực được lưu local. Dương không tạo ACK/kết quả Recovery giả, đặt gian vật lý AVAILABLE, khóa truy cập, miễn nợ, đổi trạng thái Rental hoặc hoàn tất. Thực thi Recovery/kết quả/tích hợp đọc thực thuộc chủ nguồn; endpoint này chỉ điều phối.

MVP KHÔNG cung cấp endpoint tính phí/miễn phí/phạt mới hoặc endpoint Customer thanh toán nghĩa vụ. Phí đã được chủ nguồn xác lập vẫn nằm trong outstanding có thẩm quyền; tải lại/theo dõi xử lý không thu phí lần nữa.

## Nguồn, lưu trữ và triển khai

`service/overdue/OverdueSources.java`: FinancialSource, TermSource (chỉ đọc policy BO/nguồn gốc ngày), ReminderSource (policy + đưa vào hàng đợi trong giao dịch), RecoverySource (bên nhận thực tham gia giao dịch). Không cài dữ liệu giả/lớp triển khai mặc định chạy thực tế. Thiếu nguồn => đọc PARTIAL hoặc lệnh trả 409 DEFERRED_SOURCE; không trả 0/[] như thể thành công.

Bổ sung overdue_follow_up_states và overdue_follow_ups; lịch sử bất biến, tác vụ này không xóa hoặc xóa mềm. Tái sử dụng cơ chế idempotency D2 với không gian tên thao tác overdue_follow_up/overdue_recovery và payload ràng buộc Rental+hồ sơ. Kiểm tra quyền hiện tại trước khi trả kết quả đã lưu; trả kết quả đã lưu trước khi kiểm tra phiên bản luồng cũ/tình trạng quá hạn hiện tại. Sự kiện/audit/phiên bản/kết quả dùng lại và thao tác đưa vào hàng đợi/tiếp nhận của chủ nguồn cùng commit hoặc rollback.

DDL chỉ để rà soát: docs/sql/renewal-operations-overdue-schema.sql. Không đổi cấu trúc bảng dùng chung/tạo dữ liệu mẫu/bổ sung ngược, không chạy migration hoặc bật bộ lập lịch. Triển khai đầy đủ cần bộ kết nối policy/tài chính/thông báo/Recovery, chủ nguồn rà soát schema/khóa và E2E đồng thời MySQL/Return/Settlement/Recovery.
