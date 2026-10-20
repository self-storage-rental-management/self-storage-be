# Kịch bản kiểm thử trên web: gia hạn khi quá hạn và thu hồi

- Ngày soạn: 20/10/2026.
- Trạng thái: **chưa chạy**. Tài liệu là kế hoạch kiểm thử, không phải kết quả kiểm thử.
- Phạm vi: quá hạn thời gian thuê, gia hạn trong thời gian còn được phép, thu hồi và quyết toán.
- Thanh toán: dùng phương án toàn kỳ; chưa kiểm thử chậm thanh toán từng tháng vì nhóm đang tạm dừng nghiệp vụ này.
- Mọi mốc bên dưới dùng giờ Việt Nam.

> Cập nhật điều kiện ngày 20/10/2026: kỳ thuê được đọc trực tiếp từ ngày bắt đầu/kết thúc trên Rental, không còn bắt buộc bản xác nhận kỳ thuê, nhận kho/nhật ký xác nhận hoặc lịch sử hoàn tất gia hạn. Các ca thiếu bằng chứng lịch sử không còn được mong đợi bị chặn chỉ vì thiếu bằng chứng; chỉ thiếu/sai ngày thuê mới chặn việc xác định kỳ thuê.

**Bộ dữ liệu local đã tạo ngày 20/10/2026:** xem [Danh sách hồ sơ và cách mở trên web](OVERDUE_WEB_DEMO_DATA.md). Bộ này gồm 13 hồ sơ nền và 6 nhiệm vụ đang mở; các ca chính xác giờ/lỗi đối soát chưa được chuẩn bị đầy đủ.

## 1. Chuẩn bị trước khi quay video hoặc kiểm thử

1. Khởi động MySQL, áp dụng migration `2026102001`, chạy BE và FE cùng phiên bản code.
2. Bật nguồn kỳ thuê đã xác nhận, nhiệm vụ thu hồi và tác vụ tự động thu hồi bằng bộ khởi động local hiện có. Chờ bộ chuyển hồ sơ cũ đối chiếu xong; hồ sơ dùng thử phải đọc được kỳ thuê hợp lệ.
3. Chuẩn bị ba phiên trình duyệt riêng: **khách hàng**, **quản lý**, **nhân viên** cùng cơ sở. Có thể dùng ba hồ sơ trình duyệt để tránh đăng xuất qua lại.
4. Quản lý có quyền xem/xử lý hồ sơ thuê. Nhân viên có quyền vận hành, xử lý trả kho và ghi nhận thanh toán tại cơ sở thử.
5. Chuẩn bị các tệp giả lập: biên lai, biên bản kiểm kê, biên bản hoàn tất thu hồi. Dùng đúng loại tệp mà màn hình cho phép.
6. Mỗi kịch bản thay đổi trạng thái dùng một hồ sơ thuê và một gian kho riêng. Ghi mã hồ sơ trước khi thao tác; không dùng lại hồ sơ đã hoàn tất cho một nhánh khác.
7. Dữ liệu quyết toán phải có chứng từ và công nợ hợp lệ. Không đặt trước một số tiền tùy ý rồi xem đó là kết quả hệ thống phải trả.

**Giới hạn hiện tại:** web chưa có nút tạo bộ hồ sơ quá hạn hoặc tua thời gian. Phải chuẩn bị dữ liệu demo có kỳ thuê và bằng chứng hợp lệ trước khi chạy. Không chỉ sửa `rentals.contract_end_date`, không sửa bản xác nhận cũ để ép hồ sơ quá hạn, không đổi giờ hệ điều hành.

Các ca đúng giờ ranh giới, sự cố đối soát và chạy đồng thời cần môi trường thử được chuẩn bị riêng ở BE; không thể hoàn thành chỉ bằng bấm trên web hiện tại. Ghi **chưa đủ điều kiện chạy**, không đánh dấu đạt cho các ca đó.

## 2. Bộ dữ liệu cần có

Gọi **D** là ngày cuối được sử dụng kho do BE đã xác nhận, không tự suy ra D từ ngày thô trong database.

| Mã dữ liệu | Tình huống cần chuẩn bị | Trạng thái ban đầu mong đợi |
| --- | --- | --- |
| DL-01 | D là hôm nay hoặc ngày tương lai | Chưa quá hạn |
| DL-02 | Hôm nay là D + 1 | Cảnh báo |
| DL-03 | Hôm nay là D + 4 | Nghiêm trọng |
| DL-04 | Hôm nay là D + 7, thời gian BE trước 17:00 | Khẩn cấp |
| DL-05 | D + 7 sau 17:00, hoặc D + 8 trước 08:00 | Chờ thu hồi, chưa tự tạo nhiệm vụ |
| DL-06 | Từ D + 8 lúc 08:00, không có giao dịch tiền chưa rõ kết quả, không có trả kho đang mở | Đủ điều kiện tạo nhiệm vụ |
| DL-07 | Như DL-06 nhưng có giao dịch gia hạn đang chờ đối soát | Cần đối soát, chưa tự hủy gia hạn và chuyển nhiệm vụ |
| DL-08 | Đã có nhiệm vụ thu hồi đang mở | Chờ thu hồi |
| DL-09 | Đã có nhiệm vụ đang xử lý, chưa kiểm kê | Đang thu hồi |
| DL-10 | Đã thanh toán cọc gia hạn, chưa hoàn tất ký | Ngày kết thúc thuê chưa được gia hạn |

Ví dụ nếu ngày BE là **20/10/2026**: DL-02 có D = 19/10; DL-03 có D = 16/10; DL-04 có D = 13/10 trước 17:00; DL-06 có D = 12/10 từ 08:00. Đây là yêu cầu cho dữ liệu demo, không phải hướng dẫn sửa ngày của hồ sơ thật.

Hạn ký gia hạn thông thường được giới hạn bởi **thời điểm thanh toán cọc + 3 ngày**, đồng thời không vượt **17:00 ngày D + 7**. Lịch ký còn phải có chỗ và nằm trong giờ hoạt động. Hạn thanh toán cọc hiển thị trên hồ sơ là một mốc riêng, phải kiểm tra trước khi mở cổng thanh toán.

## 3. Thứ tự chạy nhanh cho buổi review

1. Mở DL-02 và DL-03: trình bày các mức cảnh báo.
2. Chạy TC-03: gia hạn thành công khi đã quá hạn.
3. Chạy TC-04: chỉ trả cọc chưa làm hết quá hạn.
4. Chạy TC-06: hết hạn gia hạn nhưng chưa đến giờ tạo thu hồi.
5. Chạy TC-07 → TC-08 → TC-09 → TC-10: thu hồi đầy đủ.
6. Chạy TC-11 và TC-12: kiểm tra chặn thiếu biên lai và chưa dọn kho.

Chuẩn bị sẵn các hồ sơ độc lập để không phải chờ thời gian thực chuyển từ ngày thứ 1 sang ngày thứ 8 trong video.

## 4. Các kịch bản chính

### TC-01 — Hồ sơ chưa quá hạn

- **Dữ liệu:** DL-01.
- **Thao tác:** khách mở hồ sơ thuê; quản lý mở **Theo dõi quá hạn**, tìm theo mã hồ sơ.
- **Mong đợi:** không có cảnh báo quá hạn thời gian thuê; không xuất hiện trong bộ lọc quá hạn thời gian thuê; không có nhiệm vụ thu hồi được tạo do quá hạn.

### TC-02 — Các mức cảnh báo đúng ngày

- **Dữ liệu:** DL-02, DL-03, DL-04.
- **Thao tác:** quản lý tìm lần lượt từng hồ sơ ở **Theo dõi quá hạn**; khách mở hồ sơ tương ứng.
- **Mong đợi:** số ngày đúng theo D; trạng thái lần lượt **Cảnh báo**, **Nghiêm trọng**, **Khẩn cấp**. Hạn cuối là 17:00 ngày D + 7, bắt đầu thu hồi từ 08:00 ngày D + 8; các màn hình không lệch ngày do múi giờ.
- **Lưu ý:** trạng thái Khẩn cấp không bảo đảm vẫn còn lịch ký trống. Kiểm tra lịch hợp lệ trước khi chọn hồ sơ cho ca gia hạn thành công.

### TC-03 — Gia hạn thành công khi đang quá hạn

- **Dữ liệu:** một bản riêng của DL-02 hoặc DL-03, còn lịch ký hợp lệ; chuẩn bị hoàn tất trước cả hạn ký và hạn chặn thu hồi.
- **Khách:** mở hồ sơ thuê → gửi yêu cầu gia hạn → chọn gói và điều khoản → lưu lịch ký tương lai hợp lệ → thanh toán cọc thành công qua luồng thử hiện có.
- **Nhân viên:** mở **Ký gia hạn** → ghi nhận khách đến → đối chiếu bảng kê → ghi nhận số còn lại kèm biên lai → hoàn tất gia hạn.
- **Quản lý:** tải lại hồ sơ thuê và danh sách quá hạn, rồi chờ thêm một lượt quét thu hồi.
- **Mong đợi:** ngày hết thuê đổi đúng kỳ đã chấp nhận; có sự kiện hoàn tất và biên lai; phí quá hạn phát sinh được đối chiếu theo bảng kê, không tự bỏ qua. Nếu ngày hết thuê mới chưa quá hạn, hồ sơ ra khỏi danh sách quá hạn và không phát sinh nhiệm vụ thu hồi.

### TC-04 — Trả cọc nhưng không hoàn tất ký

- **Dữ liệu:** DL-10 độc lập, vẫn đang quá hạn.
- **Thao tác:** khách thanh toán cọc thành công nhưng nhân viên chưa hoàn tất gia hạn; quản lý tải lại hồ sơ.
- **Mong đợi:** ngày hết thuê giữ nguyên; hồ sơ vẫn quá hạn, không được xem là đã gia hạn chỉ vì có cọc.
- **Nhánh tiếp:** dùng dữ liệu riêng đã đến D + 8 để kiểm tra thu hồi. Trạng thái cọc phải theo hồ sơ đối soát, không kết luận mất cọc chỉ từ việc đã tạo nhiệm vụ.

### TC-05 — Quay lại từ cổng thanh toán khi chưa trả tiền

- **Dữ liệu:** yêu cầu gia hạn còn hạn thanh toán, có lịch ký hợp lệ và chưa có thanh toán thành công.
- **Thao tác:** mở VNPay → quay về hoặc hủy ở cổng thanh toán → tải lại chi tiết yêu cầu → thử thanh toán lại.
- **Mong đợi:** không ghi nhận trả tiền khi chưa có kết quả xác thực. Sau khi lần thử trước được xác định thất bại/hủy hoặc hết hiệu lực, có thể thanh toán lại nếu hồ sơ vẫn đủ điều kiện. Không bị khóa vĩnh viễn chỉ vì từng mở VNPay; nếu đang chờ xác minh phải hiển thị lý do. Chưa thanh toán không làm đổi ngày thuê.

### TC-06 — Đóng gia hạn trước giờ bắt đầu thu hồi

- **Dữ liệu:** DL-05.
- **Thao tác:** khách thử mở/gửi gia hạn hoặc tiếp tục yêu cầu cũ; quản lý mở quá hạn và chờ một lượt quét.
- **Mong đợi:** gia hạn bị chặn theo hạn; hiển thị **Chờ thu hồi**. Trước 08:00 ngày D + 8 không tự tạo nhiệm vụ do mốc ngày thứ 8. Kho vẫn đang được sử dụng, chưa được giải phóng.

### TC-07 — Tạo đúng một nhiệm vụ thu hồi

- **Dữ liệu:** DL-06. Không có trả kho đang mở hoặc giao dịch tiền chưa rõ kết quả.
- **Thao tác:** quản lý vào **Theo dõi quá hạn**, chờ tác vụ tự động quét, tải lại; mở nhiệm vụ, ghi mã. Tải lại và chờ thêm lượt quét.
- **Mong đợi:** có đúng một nhiệm vụ gắn đúng khách, hồ sơ và cơ sở; trạng thái **Chờ thu hồi**. Các lượt quét sau không tạo thêm nhiệm vụ. Tạo nhiệm vụ chưa giải phóng kho, chưa tự khẳng định mất cọc.
- **Thời gian:** bộ quét mặc định có độ trễ khởi động và khoảng lặp 60 giây; quan sát ít nhất hai lượt quét thành công. Nếu không xuất hiện, kiểm tra nguồn dữ liệu/chính sách và lý do hoãn ở BE, không đánh dấu lỗi ngay khi vừa tải trang.

### TC-08 — Nhân viên bắt đầu xử lý

- **Dữ liệu:** nhiệm vụ TC-07 hoặc DL-08.
- **Thao tác:** nhân viên mở nhiệm vụ qua màn hình vận hành hiện có; nhập ghi chú → **Bắt đầu xử lý**. Quản lý tải lại quá hạn.
- **Mong đợi:** chuyển **Đang thu hồi**; ghi đúng người và thời điểm nhận; không thể bắt đầu lại để sinh thêm lần tiếp nhận. Gian kho chưa chuyển còn trống.

### TC-09 — Kiểm kê và xác nhận quyết toán

- **Dữ liệu:** nhiệm vụ TC-08 hoặc DL-09.
- **Nhân viên:** ghi tình trạng tài sản, chìa khóa/thẻ/khóa, phân loại hư hỏng, chi phí theo chứng từ, tình trạng kho đề xuất; tải biên bản → **Lưu kiểm kê**.
- **Khách:** mở hồ sơ trả kho liên quan → kiểm tra và xác nhận quyết toán.
- **Mong đợi:** một hồ sơ quyết toán liên kết đúng nhiệm vụ; số tiền theo công nợ và chứng từ. Chưa thu/hoàn khoản còn lại thì chưa được hoàn tất thu hồi. Không tạo thêm hồ sơ kiểm kê khi gửi lại cùng thao tác đã thành công.

### TC-10 — Thu/hoàn tiền và hoàn tất thu hồi

- **Dữ liệu:** TC-09 đã xác nhận quyết toán. Chuẩn bị riêng nhánh **phải thu thêm**, **phải hoàn**, và **không thu/hoàn thêm** nếu có dữ liệu hợp lệ.
- **Nhân viên:** với nhánh phát sinh tiền, chọn phương thức, điền tham chiếu theo yêu cầu form, tải biên lai, xác nhận đã thực thu/hoàn → **Ghi nhận quyết toán**. Nhánh không phát sinh tiền phải đi theo trạng thái quyết toán của BE, không ghi biên lai số tiền 0 giả.
- **Nhân viên:** xác nhận đã dọn tài sản và kiểm tra kho, nhập ghi chú, tải biên bản hoàn tất → **Hoàn tất thu hồi và giải phóng kho**.
- **Mong đợi:** thu/hoàn đúng chiều và số tiền; nhiệm vụ **Hoàn tất**, hồ sơ thuê kết thúc. Kho chuyển đúng tình trạng sau kiểm tra: có thể còn trống hoặc bảo trì theo kết quả, không mặc định cho thuê lại nếu kho hư hỏng. Quản lý vẫn mở được lịch sử và chứng từ; khách có thông báo hoàn tất.

## 5. Các trường hợp chặn và ngoại lệ

| Mã | Chuẩn bị và thao tác | Kết quả mong đợi |
| --- | --- | --- |
| TC-11 | Tại bước thu/hoàn tiền, bỏ biên lai hoặc bỏ xác nhận thực thu/hoàn rồi gửi | Bị chặn; không ghi nhận quyết toán thành công, không đổi công nợ như thể đã nhận tiền |
| TC-12 | Thử hoàn tất khi chưa quyết toán xong, chưa dọn tài sản, chưa xác nhận kiểm tra kho hoặc thiếu biên bản hoàn tất; chạy riêng từng trường hợp | Bị chặn; nhiệm vụ chưa hoàn tất, kho chưa giải phóng |
| TC-13 | Khách khiếu nại quyết toán ở TC-09 | Giữ nhánh tranh chấp; quản lý xử lý trên hồ sơ trả kho; không tự coi là khách đã đồng ý hoặc tự hoàn tất thu hồi |
| TC-14 | Dữ liệu DL-07, chờ tác vụ tự động quét | Không tự hủy gia hạn và chuyển nhiệm vụ khi tiền chưa rõ kết quả. Đối soát xong, nếu không còn vướng và vẫn quá hạn thì lượt quét sau mới xử lý. Cần dữ liệu thử BE cho trạng thái giao dịch này |
| TC-15 | Có cọc đã xác thực, đủ điều kiện thu hồi nhưng cố tình làm bộ đối soát cọc lỗi riêng trong môi trường thử | Lỗi đối soát cọc không làm mất nhiệm vụ đã tạo. Khôi phục bộ đối soát và chạy lại không ghi sổ cọc hai lần. Không dùng giao dịch tiền chưa rõ kết quả để thay thế ca này |
| TC-16 | Gia hạn không hoàn tất do sự cố của cơ sở, có hồ sơ xác minh trách nhiệm hợp lệ; đến hạn xử lý cọc | Không tự kết luận khách mất cọc; đi theo nhánh rà soát/hoàn cọc. Tiến độ nhiệm vụ và kết quả xử lý tiền được kiểm tra riêng |
| TC-17 | Hai quản lý cùng gửi chuyển thu hồi, đồng thời tác vụ tự động quét trên cùng DL-06 | Chỉ một nhiệm vụ. Bên còn lại có thể nhận thông báo đã xử lý/phiên bản cũ và phải tải lại; không bắt buộc mọi yêu cầu cùng trả thành công |
| TC-18 | Hai nhân viên mở cùng một nhiệm vụ; một người xử lý rồi người còn lại gửi form cũ | Chặn phiên bản cũ; tải lại hiện trạng mới, không ghi kiểm kê/biên lai/hoàn tất trùng |
| TC-19 | Người thuộc cơ sở khác thử mở mã hồ sơ/nhiệm vụ, hoặc khách thử truy cập thao tác nhân viên | Không xem hoặc sửa được dữ liệu ngoài quyền; không chỉ ẩn nút ở FE |
| TC-20 | Thiếu ngày bắt đầu/kết thúc hoặc kỳ thuê không hợp lệ | Chặn tính kỳ gia hạn; không tự đoán ngày. Nếu ngày thuê hợp lệ, việc thiếu bản xác nhận/nhận kho/lịch sử gia hạn không còn là lý do chặn |
| TC-21 | Nhiệm vụ đã hoàn tất: tải lại, thử mở lịch sử, gửi nhắc quá hạn và tạo thu hồi lại | Xem được lịch sử; không gửi nhắc quá hạn hoặc tạo thêm nhiệm vụ. Kho giữ đúng trạng thái hoàn tất |
| TC-22 | Khách trả kho thường trước giờ thu hồi và hoàn tất bàn giao; chờ lượt quét | Không tạo thu hồi cho hồ sơ đã trả xong. Nếu trả kho mới đang mở, hệ thống phải yêu cầu đối chiếu hồ sơ đang có, không tạo song song một quy trình thu hồi |

## 6. Các ca ranh giới thời gian cần môi trường thử riêng

Không cần chờ nhiều ngày thật. Cần chuẩn bị đồng hồ thử có kiểm soát ở BE và dữ liệu phù hợp trước khi chạy các ca này; hiện chưa có chức năng đó trên web.

| Mốc thử | Điều cần kiểm tra |
| --- | --- |
| D lúc 23:59:59 → D + 1 lúc 00:00:00 | Chuyển từ chưa quá hạn sang ngày quá hạn 1 theo giờ Việt Nam |
| D + 3 → D + 4 | Cảnh báo chuyển Nghiêm trọng |
| D + 6 → D + 7, trước 17:00 | Nghiêm trọng chuyển Khẩn cấp |
| D + 7 lúc 16:59:59 | Được hoàn tất một yêu cầu đã đủ điều kiện nếu chưa hết hạn ký riêng; không dùng việc tạo lịch mới sát giờ để chứng minh ca này |
| D + 7 đúng 17:00:00 | Ghi nhận chính xác quy tắc bao gồm/không bao gồm mốc hạn; đối chiếu màn hình rủi ro, trạng thái, thanh toán và lệnh hoàn tất |
| D + 7 lúc 17:00:01 | Không hoàn tất gia hạn; Chờ thu hồi; chưa tạo nhiệm vụ ngày thứ 8 |
| D + 8 lúc 07:59:59 → 08:00:00 | Chỉ từ 08:00 mới đủ điều kiện tạo nhiệm vụ; nhiệm vụ xuất hiện sau lượt quét thành công |
| Hạn ký riêng sớm hơn hạn chặn thu hồi | Chặn ký theo hạn riêng đã hết, không cho kéo dài chỉ vì chưa sang ngày thứ 8 |
| Hoàn tất gia hạn và lượt quét thu hồi chạy đồng thời | Chỉ một hướng hợp lệ thắng theo khóa/kiểm tra điều kiện; không vừa hoàn tất gia hạn vừa tạo thu hồi trái với kỳ thuê đã cập nhật |

**Điểm cần xác minh đã thấy khi đọc code:** phần rủi ro/quyền tiếp tục gia hạn dùng điều kiện trước hạn (`now < cutoff`), trong khi một số kiểm tra lệnh vận hành cho phép bằng hạn (`now <= cutoff`). Vì vậy chưa khẳng định toàn bộ hệ thống đồng nhất tại đúng 17:00. Ghi ca này là **cần xác minh**, không dùng câu “đúng 17:00 chắc chắn được ký” làm kết quả mặc định. Kiểm thử chính xác mốc này cần đồng hồ BE có kiểm soát, không bấm bằng tay theo đồng hồ trình duyệt.

## 7. Mẫu ghi kết quả gửi nhóm

| Mã ca | Mã hồ sơ thuê / nhiệm vụ | Tài khoản / cơ sở | Thời gian BE (giờ Việt Nam) | Kết quả thực tế | Kết luận | Ảnh/video |
| --- | --- | --- | --- | --- | --- | --- |
| TC-… | … | … | … | … | Chưa chạy / Chưa đủ điều kiện chạy / Đạt / Lỗi | … |

Khi báo lỗi, ghi bước cuối đã làm, trạng thái trước/sau, thông báo trên màn hình, số tiền và mã biên lai nếu liên quan. Nếu cần đối chiếu kỹ thuật, đính kèm mã yêu cầu hoặc mã tương quan trong phản hồi API; che thông tin đăng nhập và dữ liệu cá nhân.

## 8. Điều kiện kết thúc buổi review

- Hoàn tất ít nhất TC-01 → TC-12 với dữ liệu hợp lệ; ghi rõ ca nào chưa có dữ liệu.
- Có bằng chứng một hồ sơ quá hạn gia hạn thành công và một hồ sơ đi hết thu hồi.
- Không nhân đôi nhiệm vụ/biên lai, không giải phóng kho trước khi xong kiểm kê, quyết toán và xử lý tài sản.
- Thống nhất hoặc ghi nhận lỗi ở đúng mốc 17:00; không coi các ca thời gian chính xác là đã đạt nếu chỉ xem giao diện.
- Lưu kết quả theo từng ca; kịch bản chưa chạy không được tính là đã kiểm thử thành công.
