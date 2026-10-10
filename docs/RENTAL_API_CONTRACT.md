# Đặc tả API đọc hồ sơ thuê

Phạm vi đã triển khai: chỉ gồm bốn endpoint GET. Không bao gồm kích hoạt hồ sơ thuê, thanh toán, phân gian, quyết định gia hạn hoặc tạo hợp đồng pháp lý.

## Swagger

Chạy BE bằng cấu hình local hiện có, sau đó mở `/swagger-ui/index.html` trên địa chỉ BE (cổng Spring mặc định là 8080 nếu cấu hình của bạn không ghi đè). Tái sử dụng `/v3/api-docs` và bearerAuth hiện có. Các thẻ: **D1 - Customer Rentals**, **D1 - Manager Rentals**. Đăng nhập bằng Auth API hiện có, sao chép accessToken vào **Authorize**, rồi chọn **Try it out**. Không công khai token hoặc đưa thông tin đăng nhập production/staging vào nhật ký kiểm thử.

- GET /api/customer/rental-records và /api/customer/rental-records/{id}: yêu cầu vai trò CUSTOMER và xác định chủ sở hữu từ JWT. API đặt chỗ Customer hiện có kiểm tra quyền sở hữu mà không cần cấp sẵn VIEW_RENTALS cho Customer; D1 tuân theo cách này và không thay đổi cơ chế xác thực/phân quyền.
- GET /api/manager/rentals và /api/manager/rentals/{id}: yêu cầu MANAGER + VIEW_RENTALS + phạm vi cơ sở READ. Không tự động cấp quyền. Thiếu quyền hoặc phạm vi trả 403; người dùng đã được cấp quyền nhưng truy vấn chi tiết ngoài phạm vi được xem nhận 404.

### Tương thích với API Customer dùng chung (09/10/2026)

`GET /api/customer/rentals` của `CustomerRentalController` và `CustomerRentalService` được giữ nguyên: DTO phẳng `CustomerRentalResponse`, bộ lọc và kiểm tra quyền của nguồn dùng chung. D1 cung cấp mô hình đọc có quan hệ lồng nhau và chi tiết tại namespace riêng `/api/customer/rental-records`, qua `CustomerRentalReadController` và `RentalQueryService`. Hai API đọc cùng bản ghi Rental, không tạo bảng hay sao chép dữ liệu, không chuyển hướng hoặc fallback giữa hai contract. FE D1 gọi namespace mới; client `customerRentalApi.ts` của teammate giữ nguyên. Các lệnh D2 `/api/customer/rentals/{id}/renewal-options`, `/renewal-quote`, `/renewal-requests` không đổi.

Danh sách: page=0, size=20 (1–100), status theo giá trị chuẩn, search (tối đa 200 ký tự sau khi bỏ khoảng trắng đầu/cuối), endFrom/endTo là ngày ISO bao gồm cả hai đầu mút; mặc định sort=createdAt,desc. Các trường sắp xếp: createdAt,contractEndDate,startDate,monthlyPrice,id; chiều asc/desc; dùng id ASC để phân định khi giá trị bằng nhau. Manager có thể truyền facilityId; nếu bỏ trống thì lấy hợp các phạm vi READ. Tìm kiếm theo chuỗi con mã gian kho, không phân biệt hoa thường và không diễn giải ký tự đại diện, hoặc theo UUID Rental chính xác; Manager còn có thể tìm theo fullName của khách hàng. Không tìm kiếm xuyên quyền sở hữu.

Tham số không được hỗ trợ hoặc bị lặp (kể cả needsAttention) trả 400. Endpoint chi tiết không nhận tham số truy vấn. Phân trang và cấu trúc bao phản hồi tái sử dụng các lớp dùng chung. Giá lấy từ Rental.monthlyPrice; UnitType được xác định qua StorageUnit; khi thiếu dữ liệu tài chính/truy cập luôn trả UNKNOWN/null, không suy thành PAID hoặc số 0. Không trả mã PIN nguyên văn. Quan hệ cốt lõi không khớp trả 409 mà không làm lộ ID bản ghi liên quan. Thao tác đọc không sửa chữa dữ liệu dùng chung.

## Điều kiện tích hợp

### Bộ kết nối chỉ đọc do chủ nguồn cung cấp (06/10/2026)

`RentalReadSources` cung cấp các giao diện tích hợp Spring tùy chọn: `FinancialSource`, `AccessSource`, `DateSource`. Không cài đặt lớp triển khai chạy thực tế hoặc bản ghi mặc định. Khi thiếu nguồn, phản hồi vẫn trả UNKNOWN và cảnh báo về ngày đang lưu.

- Chủ nguồn tài chính cung cấp rentalId, checkedAt, các tổng tiền VND, số **tiền bảo đảm kho** tùy chọn, nextDueDate và billingMode. Đây không phải tiền trả trước của Booking hay tiền cọc Renewal. Các tổng tiền phải không âm, overdue <= outstanding, ID Rental phải khớp và checkedAt không được ở tương lai. Dữ liệu tổng hợp không hợp lệ hoặc chưa đầy đủ không được công bố là COMPLETE.
- DTO tài chính bổ sung `securityDepositAmount` và `billingMode` (có thể null). PREPAID_FULL_PERIOD không có nextDueDate định kỳ; riêng trạng thái này không chứng minh rằng không còn nghĩa vụ phải trả chính thức. Tiền bảo đảm null vẫn là chưa xác định, kể cả khi các tổng tiền tài chính khác đã đầy đủ.
- `financialSummary.completeness`: `COMPLETE` chỉ khi tổng outstanding/overdue, billingMode và securityDepositAmount đều đã xác thực. Các tổng/billing hợp lệ nhưng securityDepositAmount còn null trả `PARTIAL`, giữ nguyên tổng đã biết và reason phần thiếu. Không có/không hợp lệ nguồn tổng tài chính trả `UNKNOWN`, không công bố các tổng chưa xác thực. Cọc bảo đảm bằng 0 chỉ là dữ liệu biết chắc khi nguồn có thẩm quyền trả rõ 0; không tự suy từ null. Đây là bổ sung giá trị completeness, không đổi shared records, giá lịch sử hoặc payment flow; FE Dương nhận cả ba trạng thái.
- Chủ nguồn truy cập chỉ cung cấp trạng thái định kiểu (ACTIVE/INACTIVE/SUSPENDED/REVOKED/EXPIRED), ID Rental khớp và checkedAt không ở tương lai. Không bao giờ trả PIN hoặc nội dung thông tin truy cập qua giao diện này.
- Chủ nguồn ngày cung cấp tham chiếu chứng minh nguồn gốc cho từng bản ghi và đúng startDate/inclusiveEndDate đang lưu. Chỉ bằng chứng khớp mới loại bỏ cảnh báo. GET không chuyển đổi hoặc bổ sung ngược dữ liệu ngày.
- Các nguồn phải chỉ đọc, có thẩm quyền và bảo đảm độ bao phủ/độ mới phù hợp với đặc tả của chủ nguồn. Không âm thầm thay ngoại lệ bằng dữ liệu demo hoặc số 0. Không đăng ký nhiều lớp triển khai cạnh tranh cho cùng một giao diện tích hợp.

Lược đồ chi tiết Swagger công bố các trường tài chính bổ sung. Giá đã áp dụng trong quá khứ vẫn là Rental.monthlyPrice. Các bộ kết nối không tạo Rental hoặc viết lại bản ghi Booking/Payment/thông tin truy cập dùng chung.

Việc kích hoạt Rental/chuyển giao giá áp dụng và ý nghĩa ngày của dữ liệu cũ cần nguồn tích hợp đã được xác minh. API trả ngày đang lưu kèm cảnh báo rõ ràng vì hiện chưa có nguồn chứng minh nguồn gốc ở cấp bản ghi; API không tự bổ sung ngược ngày. Quyền/phạm vi Manager phải được cấu hình qua quy trình phân quyền hiện có. Không tạo tài khoản mẫu hoặc bản ghi nghiệp vụ để phục vụ Swagger. Truy vấn có quyền nhưng trả rỗng không chứng minh luồng kích hoạt Booking→Rental đã hoạt động. Tích hợp tài chính/truy cập và nghĩa vụ/needsAttention được hoãn lại; chỉ kiểm thử đơn vị không đủ để hoàn tất nghiệm thu E2E khi chạy thực tế.

## Kiểm tra thủ công

1. Customer chỉ thấy hồ sơ thuê của mình; chi tiết thuộc Customer khác trả 404.
2. Manager có VIEW_RENTALS và phạm vi READ chỉ thấy các cơ sở đó; bỏ facilityId thì bao phủ tất cả cơ sở được phép.
3. Thiếu quyền/không có phạm vi trả 403; không có token trả 401; chi tiết ngoài phạm vi trả 404.
4. size101, status không được hỗ trợ, tham số lặp, ngày không hợp lệ/đảo ngược hoặc needsAttention trả 400.
5. Giá VND đã áp dụng không đổi khi danh mục cập nhật; UnitType đúng; không có PIN; tài chính UNKNOWN.
6. Tổng số/phân trang/bộ lọc/sắp xếp ổn định; GET không ghi dữ liệu; quan hệ cốt lõi không khớp trả 409.

Không cung cấp tài khoản chạy thực tế, ID giả hoặc dữ liệu thay thế khi API lỗi. Dùng bản ghi local thật; dữ liệu mẫu kiểm thử chỉ nằm trong các bài test.

## Kiểm thử tự động

Chạy `mvn -Dtest=RentalQueryTests,RentalQueryServiceTests,RentalReadIntegrationTests,RentalSwaggerTests,CancelledReservationUnitReleaseServiceTests test` với profile test/dữ liệu H2 do các bài test cung cấp. RentalSwaggerTests sử dụng cấu hình Security production dùng chung, không có bộ chọn decoder riêng cho test. Nghiệm thu chạy thực tế còn cần quyền/phạm vi thật, việc chuyển giao kích hoạt và xác minh tích hợp cơ sở dữ liệu.
