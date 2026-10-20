# Luồng quá hạn và thu hồi đã đơn giản hóa

Kịch bản kiểm thử trên web và dữ liệu cần chuẩn bị: [Gia hạn khi quá hạn và thu hồi](OVERDUE_RENEWAL_WEB_TEST_SCENARIOS.md).

## Nguồn kỳ thuê

- Khi khách xác nhận nhận kho, hệ thống lưu kỳ thuê đã xác nhận trong cùng giao dịch tạo hồ sơ thuê.
- Khi hoàn tất gia hạn, hệ thống lưu kỳ thuê mới trong cùng giao dịch cập nhật ngày hết thuê và sự kiện hoàn tất.
- Màn hình quá hạn đọc kỳ thuê đã xác nhận; không dựng lại toàn bộ lịch sử nhận kho/gia hạn mỗi lần mở màn hình.
- Hồ sơ cũ được đối chiếu lịch sử theo từng lô tối đa 100 hồ sơ. Chỉ hồ sơ có bằng chứng hợp lệ mới được ghi nhận. Không sửa ngày thuê hoặc bằng chứng cũ.
- Hồ sơ chưa được đối chiếu hoặc dữ liệu hiện tại không khớp kỳ đã xác nhận vẫn được đánh dấu thiếu nguồn dữ liệu. Không tự xem ngày sửa trực tiếp trong cơ sở dữ liệu là ngày hợp lệ.
- Bảng mới: `confirmed_rental_periods`, migration `2026102001`. Mỗi lần xác nhận là một bản ghi mới, có nguồn tham chiếu duy nhất theo hồ sơ thuê.

## Trạng thái trên giao diện

| Điều kiện | Hiển thị |
| --- | --- |
| Ngày quá hạn 1–3 | Cảnh báo |
| Ngày quá hạn 4–6 | Nghiêm trọng |
| Ngày quá hạn 7, trước hạn cuối | Khẩn cấp |
| Đã đến hạn cuối hoặc nhiệm vụ đang mở, chưa bắt đầu xử lý | Chờ thu hồi |
| Nhân viên đã bắt đầu nhiệm vụ | Đang thu hồi |
| Nhiệm vụ đã đóng sau kiểm kê, quyết toán và bàn giao kho | Hoàn tất |

BE tính ngày quá hạn, hạn cuối và giờ bắt đầu thu hồi theo giờ Việt Nam. Chính sách hiện tại giữ hạn cuối 17:00 ngày quá hạn thứ 7 và bắt đầu thu hồi từ 08:00 ngày thứ 8. FE chỉ hiển thị trạng thái và mốc do BE trả về.

Hồ sơ đã có nhiệm vụ tiếp tục xuất hiện để theo dõi tiến độ, kể cả sau khi hoàn tất. Nút mở nhiệm vụ dùng lại màn hình thu hồi hiện có. Không cho tạo lại nhiệm vụ hoặc gửi nhắc quá hạn khi nhiệm vụ đã hoàn tất.

## Tách tiền khỏi nhiệm vụ

- Trang theo dõi mặc định xem quá hạn thời gian thuê. Dữ liệu nghĩa vụ thanh toán vẫn có bộ lọc riêng; khoản tiền đến hạn không tự tạo nhiệm vụ thu hồi.
- Tạo nhiệm vụ không còn gọi trực tiếp việc quyết định mất cọc/ghi sổ cọc gia hạn. Bộ xử lý đối soát cọc chạy riêng, trong giao dịch riêng; lỗi ghi sổ không làm mất nhiệm vụ đã tạo.
- Giữ kiểm tra giao dịch gia hạn đang chờ hoặc đã thanh toán nhưng chưa khớp trạng thái. Các trường hợp này cần đối soát trước khi tự hủy gia hạn và chuyển nhiệm vụ; không coi tiền chưa rõ kết quả là chưa thanh toán.
- Giữ kiểm tra lỗi từ cơ sở và quyết định hoàn cọc. Tạo nhiệm vụ không đồng nghĩa đã mất cọc.
- Chưa bổ sung quy tắc thu hồi vì chậm thanh toán từng tháng; phần này đang chờ nhóm thống nhất.

## Một nhiệm vụ cho một hồ sơ thuê

- Bộ tiếp nhận giữ khóa hồ sơ thuê và ràng buộc duy nhất `recovery_tasks.rental_id`. Gửi lại cùng hồ sơ trả về nhiệm vụ đã nhận, không tạo nhiệm vụ mới.
- Lệnh của quản lý và tác vụ tự động dùng chung bộ tiếp nhận. Lịch sử bàn giao và khóa chống gửi lặp tiếp tục được giữ.
- Nhân viên tiếp tục dùng các bước bắt đầu nhiệm vụ → kiểm kê → quyết toán → xác nhận thu/hoàn tiền bằng biên lai → hoàn tất thu hồi.
- Không tự giải phóng gian kho khi mới quá hạn, khi tạo nhiệm vụ hoặc khi chỉ đối soát tiền.

## Vận hành sau cập nhật

Chạy migration trước khi khởi động phiên bản BE mới. Bộ chuyển hồ sơ cũ hoạt động khi bật nguồn kỳ thuê (`storagehub.integration.rental-period.enabled=true`). Trong thời gian chuyển đổi, hồ sơ chưa xác minh có thể tạm báo thiếu dữ liệu. Không chỉnh ngày thuê trực tiếp để thử quá hạn vì sẽ không khớp kỳ đã xác nhận; dữ liệu demo cần đi qua nguồn xác nhận hợp lệ.
