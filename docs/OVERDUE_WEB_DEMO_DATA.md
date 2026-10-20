# Bộ dữ liệu demo quá hạn trên web — 20/10/2026

> **Trạng thái sau phục hồi DB tối 20/10/2026:** theo yêu cầu dùng DB cũ, local đã được nạp lại từ `scripts/storagehub_database_full.sql` (xuất ngày 11/10/2026). Bộ 13 hồ sơ và các mã nhiệm vụ liệt kê bên dưới là dữ liệu của phiên trước, **không còn trong DB hiện tại**. Cần tạo lại bộ demo sau khi chuẩn bị nguồn hồ sơ hợp lệ; không dùng danh sách mã cũ để thao tác.

## Đã tạo trong database local

- Database: `storagehub_local`, container `storagehub-mysql`.
- Lô dữ liệu: **QD261020133000**. Đây là dữ liệu giả lập để thao tác, không phải giao dịch thực thu.
- 13 hồ sơ thuê mới, mỗi hồ sơ có gian kho, đặt kho, báo giá, bảng giá nhận kho, lần nhận kho và hợp đồng riêng. Dùng khách hàng demo và cơ sở Quận 1 hiện có.
- 12 hồ sơ có kỳ thuê demo đã xác nhận. Hồ sơ `12-THIEU-NGUON` cố ý không có nguồn này để thử chặn dữ liệu chưa xác minh.
- Mỗi hồ sơ có biên lai tiền mặt **giả lập**, tổng ban đầu 22.000.000 đồng: tiền thuê 3 tháng 16.500.000 đồng và cọc bảo đảm 5.500.000 đồng. Cọc giữ chỗ trong bộ thử bằng 0; đây không phải thay đổi chính sách đặt kho thật.
- Có nhật ký `LOCAL_OVERDUE_DEMO_CREATED`, nguồn kỳ thuê `local-demo:...` và mã thanh toán `MANUAL-DEMO-...`. Không có chữ ký/callback VNPay giả.
- Không sửa các hồ sơ thuê cũ. Đã sao lưu trước khi thêm: `.local/QD261020133000-before.sql`.

### Cập nhật sau khi merge develop

Khi khởi động lại ngày 20/10/2026, hai migration mới chưa áp dụng từ develop được đổi số để tránh trùng `2026101901` đã dùng cho thanh toán từng kỳ và để đứng sau `2026102001` đã chạy: thông tin liên hệ khách hàng chuyển từ `2026101900` sang `2026102002`; bảng bộ đếm mã đặt kho chuyển từ `2026101901` sang `2026102003`. Nội dung SQL giữ nguyên, không sửa lịch sử Flyway đã áp dụng. Bản sao lưu trước lần cập nhật này: `.local/before-develop-restart-20261020.sql`.

## Cách mở trên web

1. Vào **http://localhost:8443**.
2. Khách hàng đăng nhập `customer@storagehub.demo` → **Hồ sơ thuê** → tìm `QD261020133000`.
3. Quản lý đăng nhập `manager@storagehub.demo` → **Theo dõi quá hạn** → tìm cùng mã lô. Để bộ lọc **Quá thời hạn thuê**.
4. Các hồ sơ từ số 06 đến 11 đã có nhiệm vụ tự động. Quản lý có thể bấm **Mở nhiệm vụ thu hồi**. Nhân viên `staff@storagehub.demo` tiếp tục ở màn hình nhiệm vụ vận hành hiện có.
5. Khi form cần minh chứng, chọn tệp mẫu ở thư mục `.local/overdue-demo-evidence` trong repo BE; tải lên qua form để hệ thống liên kết đúng nghiệp vụ. Không nhập lại mã file của biên lai nhận kho làm file quyết toán.

Trạng thái trong bảng là tại ngày tạo **20/10/2026**. BE dùng thời gian thật; ngày mai số ngày quá hạn sẽ tăng, hồ sơ ngày 7 có thể tự chuyển thu hồi. Muốn có bộ mới theo ngày hiện tại, chạy script tạo lô mới; không sửa ngày của lô cũ.

## Danh sách để thao tác

Các mã gian kho bên dưới đều bắt đầu bằng `QD261020133000-`.

| Hậu tố gian kho | Ngày cuối sử dụng | Mục đích | Mã hồ sơ thuê |
| --- | --- | --- | --- |
| 01-CON-HAN | 04/11/2026 | Chưa quá hạn — TC-01 | `8e8e5406-0a20-4066-979c-06c8cd81c6fb` |
| 02-GIA-HAN | 19/10/2026 | Cảnh báo; thực hiện gia hạn — TC-02,03 | `1529a1b6-37ff-4b00-80c2-8637dfda3063` |
| 03-THANH-TOAN | 19/10/2026 | Tự mở VNPay, quay lại, trả cọc nhưng chưa ký — TC-04,05 | `a836ea1c-12f9-46f1-a211-8945ae770f20` |
| 04-NGHIEM-TRONG | 16/10/2026 | Quá hạn 4 ngày; gia hạn — TC-02,03 | `0d0a0e05-b72a-47bc-acbb-07427e63551f` |
| 05-KHAN-CAP | 13/10/2026 | Quá hạn 7 ngày; trước/sau 17:00 — TC-02,06 | `8a2109ca-8d12-4143-8732-ebd3aa22780a` |
| 06-THU-THEM | 12/10/2026 | Thu hồi, quyết toán có khoản phải thu — TC-07..12 | `e81aae06-53f8-4776-a6f0-3b4efcab378d` |
| 07-HOAN-TIEN | 12/10/2026 | Thu hồi, quyết toán hoàn cọc — TC-07..12 | `01ec6029-a027-4d53-8e39-ed247d41b262` |
| 08-KHONG-CHENH | 12/10/2026 | Thu hồi, quyết toán không chênh lệch — TC-07..12 | `90ec0e02-511b-46c0-a130-5c9a473034bf` |
| 09-TRANH-CHAP | 12/10/2026 | Khách khiếu nại sau kiểm kê — TC-13 | `6518b6a0-0e7b-4168-8283-53fcb084a450` |
| 10-THIEU-BIEN-LAI | 12/10/2026 | Thử gửi form thiếu chứng từ — TC-11,12 | `2359a671-46ea-4f8d-afe3-18fc5a4e3ac3` |
| 11-GUI-TRUNG | 12/10/2026 | Hai phiên cùng xử lý; quyền; xem lại — TC-18,19,21 | `36a02c9d-fc04-4013-8bf9-41663f2fc847` |
| 12-THIEU-NGUON | 18/10/2026 | Cố ý thiếu kỳ thuê xác nhận — TC-20 | `1a90f54a-10e9-4757-9d2f-915a24af77dd` |
| 13-TRA-KHO | 19/10/2026 | Khách thực hiện trả kho thường — TC-22 | `2a72d52a-ab29-495d-9197-b10df600c3b0` |

### Các nhiệm vụ đã được tự động tạo

| Gian kho | Mã nhiệm vụ |
| --- | --- |
| 06-THU-THEM | `8e7ad09e-c8bd-4f40-ab30-1adbf7ddf435` |
| 07-HOAN-TIEN | `c96cc08a-bf89-46f2-9d6c-d32091ea5ef1` |
| 08-KHONG-CHENH | `f7a38999-07a9-436e-a939-31da9bbc5421` |
| 09-TRANH-CHAP | `8e1d8fe3-cbcc-4487-87e1-6972e8115562` |
| 10-THIEU-BIEN-LAI | `e5e47550-ff0d-463a-a35f-573c953ad150` |
| 11-GUI-TRUNG | `afa5e7d8-3d10-4e5b-9429-8d76d6c2c60d` |

Các nhiệm vụ đang **mở**, chưa bắt đầu kiểm kê/quyết toán để bạn tự thao tác trên web. Tên nhánh thu thêm/hoàn/không chênh là mục đích chạy, chưa phải kết quả quyết toán đã ghi nhận. Với cọc 5.500.000 đồng và chưa có khoản thu khác: tổng khấu trừ thấp hơn cọc sẽ hoàn tiền, bằng cọc không phát sinh thu/hoàn, lớn hơn cọc sẽ phải thu thêm. Dùng số tiền minh họa trong môi trường thử và đối chiếu bảng kê BE; không coi các khoản phí giả lập là chính sách thật.

## Phạm vi đã kiểm tra và phần chưa chuẩn bị

Đã đọc lại qua API: khách thấy 13 hồ sơ; quản lý thấy đúng các mức quá hạn; 6 nhiệm vụ tự động đã tạo; một biên lai demo tải được HTTP 200. Chưa thực hiện toàn bộ các bước gia hạn, kiểm kê, quyết toán và hoàn tất thay người kiểm thử.

- Danh sách quá hạn báo thiếu nguồn do hồ sơ số 12 là **cố ý**. Hồ sơ này không được tự tạo thu hồi. Tình trạng tài chính tổng hợp có thể vẫn báo chưa xác minh đầy đủ vì runtime chưa có nguồn chứng nhận toàn bộ lịch sử công nợ; bộ dữ liệu này không bỏ qua kiểm tra đó.
- Chưa giả lập trả cọc gia hạn thành công: dùng hồ sơ số 03 để tự thanh toán thật qua môi trường VNPay Sandbox của dự án. Không có tiền thật trong các biên lai fixture.
- TC-14,15,16 cần trạng thái giao dịch/sự cố và đối soát riêng; chưa tạo hồ sơ cố ý làm lỗi bộ đối soát.
- Các mốc chính xác 17:00/08:00 và đua giữa tạo nhiệm vụ tự động/thủ công cần đồng hồ thử hoặc lô mới vào đúng thời điểm; lô này chưa có cơ chế tua giờ. TC-17 chỉ có thể quan sát không tạo lại trên nhiệm vụ đã có, chưa tái hiện đua tạo lần đầu.
- Chưa tạo tài khoản/cơ sở mới cho nhánh sai cơ sở. Có thể dùng tài khoản cơ sở khác hiện có, nếu đã được nhóm cấp sẵn.

## Tạo bộ mới vào ngày khác

Từ thư mục BE, khi Docker/MySQL local đang chạy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-overdue-web-demo.ps1
```

Script chỉ kết nối database `storagehub_local` trong container `storagehub-mysql`, lấy hồ sơ demo làm mẫu và thêm lô mới có mã `QD...`. Script sao lưu trước khi ghi, không ghi đè lô đã có, không chạy trên database chung. SQL và danh sách mã sinh ra lưu trong `.local/<mã-lô>-fixtures.sql` và `.local/<mã-lô>-manifest.json`. Các bản sao lưu và tệp tải lên không đưa vào Git.
