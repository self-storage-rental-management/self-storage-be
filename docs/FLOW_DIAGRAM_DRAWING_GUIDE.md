# StorageHub — Tài liệu vẽ 5 flow nghiệp vụ

Tài liệu này là bản đặc tả ngắn để vẽ các tab flow trong tài liệu thuyết trình. Có thể chuyển trực tiếp thành sơ đồ swimlane trên draw.io hoặc dùng Mermaid để kiểm tra nhanh.

## 1. Quy ước chung

### Tác nhân/lane

| Lane | Trách nhiệm chính |
| --- | --- |
| Customer | Tìm kho, đặt/giữ kho, thanh toán, nhận kho, gia hạn, trả kho |
| StorageHub | Kiểm tra điều kiện, khóa tài nguyên, tính tiền, đổi trạng thái, gửi thông báo |
| VNPay | Xử lý và xác thực kết quả thanh toán qua callback/IPN |
| Staff | Check-in, thu tiền tại quầy, ký/bàn giao, kiểm kê, xử lý bảo trì |
| Manager | Phân kho, quản lý tài nguyên và công việc bảo trì tại cơ sở |
| Business/Finance | Quản lý chính sách chung, báo cáo và đối soát/hoàn tiền |

### Quy tắc vẽ

- Hình chữ nhật: thao tác hoặc xử lý.
- Hình thoi: điều kiện rẽ nhánh.
- Hình trụ: dữ liệu/bằng chứng được lưu.
- Mũi tên nét đứt: thông báo/email hoặc tác vụ bất đồng bộ.
- Màu xanh: thành công; vàng: chờ khách/đối soát; đỏ: từ chối, lỗi hoặc tranh chấp.
- Mỗi flow nên có một nút **Start**, một hoặc nhiều nút **Decision**, và nút **End/State** rõ ràng.

> Nguồn ưu tiên khi vẽ là hành vi API/code hiện tại. Các tài liệu cũ có thể còn mô tả OTP ở mỗi lần đặt kho hoặc Manager phải phân công Staff cho gia hạn; đó không còn là bước bắt buộc của flow hiện hành.

## 2. FLOW 1 — Khởi tạo thuê kho và bàn giao

### Mục tiêu

Đi từ lúc khách chọn kho đến khi hàng đã được kiểm kê và kỳ thuê chính thức bắt đầu.

### Điều kiện đầu vào

- Cơ sở hoạt động, loại kho và gói thuê còn hiệu lực.
- Tài khoản đã xác minh email; khách guest chỉ xác minh email khi hệ thống yêu cầu.
- Hàng hóa có kích thước, số lượng, khối lượng và thông tin dễ vỡ hợp lệ.

### Sơ đồ chính

```mermaid
flowchart TD
    A([Start]) --> B[Customer chọn cơ sở, loại kho, kỳ thuê]
    B --> C[Nhập số lượng, kích thước, cân nặng và tình trạng hàng]
    C --> D{Hàng hóa hợp lệ và có kho khả dụng?}
    D -- Không --> E[Hiển thị lý do / yêu cầu sửa thông tin]
    E --> C
    D -- Cần duyệt --> F[Staff xem xét hàng hóa đặc biệt]
    F --> G{Staff duyệt?}
    G -- Không --> H([Reservation bị từ chối])
    G -- Có --> I[Tạo báo giá và giữ kho có TTL]
    D -- Có --> I
    I --> J[Customer chấp nhận điều khoản, chính sách không hoàn tiền và hạn giữ]
    J --> K[Thanh toán cọc qua VNPay]
    K --> L{Callback/IPN xác thực?}
    L -- Đang xử lý / chưa rõ --> M[Giữ nguyên trạng thái, đưa vào đối soát]
    M --> L
    L -- Thất bại cuối cùng / quá hạn --> N[Nhả kho và thông báo khách]
    L -- Thành công --> O[Reservation CONFIRMED]
    O --> P[Manager phân một gian kho vật lý]
    P --> Q{Khóa cạnh tranh thành công?}
    Q -- Không --> R[Chọn lại gian phù hợp]
    R --> P
    Q -- Có --> S[Staff check-in: giấy tờ, hàng hóa, hợp đồng, khoản phải thu]
    S --> T[Thu phần còn lại + cọc bảo đảm, lưu chứng từ]
    T --> U[Staff hoàn tất checklist và bàn giao kho trống]
    U --> V[Customer kiểm tra và xác nhận đã nhận kho]
    V --> W[Bắt đầu thời gian setup miễn phí]
    W --> X[Customer chuyển hàng vào kho và bấm “Đã chuẩn bị xong”]
    X --> Y[Staff kiểm kê hàng hóa sau khi xếp kho]
    Y --> Z{Customer xác nhận bắt đầu tính kỳ thuê?}
    Z -- Có --> AA[Rental ACTIVE; bắt đầu kỳ thuê theo ngày xác nhận]
    Z -- Chưa xác nhận --> AB[Chờ đến hết thời gian setup miễn phí]
    AB --> AC[Tự động bắt đầu kỳ thuê theo deadline]
    AA --> AD([End: kho OCCUPIED, Rental ACTIVE])
    AC --> AD
```

### Các điểm phải thể hiện trên sơ đồ

1. **Không gửi OTP ở mỗi lần đặt kho** đối với tài khoản đã xác minh email. OTP/email verification dùng cho đăng ký hoặc tình huống rủi ro/guest.
2. Giữ kho có TTL; giới hạn số lượt giữ chưa thanh toán và rate limit được xử lý ở hệ thống.
3. Khi chỉ còn một gian, việc kiểm tra khả dụng và gán gian phải nằm trong transaction/lock; không để hai reservation cùng nhận một gian.
4. Cọc đã thanh toán nhưng callback chưa rõ không được tự nhả kho. Chỉ giải phóng sau kết quả thất bại cuối cùng được xác minh.
5. Staff bàn giao kho trống chưa phải điểm bắt đầu tính tiền. Sau đó khách xác nhận nhận kho, chuyển hàng, Staff kiểm kê bước cuối và khách xác nhận bắt đầu tính kỳ thuê.
6. Nếu khách không xác nhận sớm, hệ thống vẫn phải có deadline setup rõ ràng để tự động chuyển sang kỳ thuê chính thức.

### State chuyển đổi để ghi cạnh mũi tên

`QUOTE/HELD → PAYMENT_PENDING → CONFIRMED → UNIT_RESERVED → CHECKIN_IN_PROGRESS → HANDOVER_PENDING_CUSTOMER → SETUP_IN_PROGRESS → INVENTORY_CHECKED → ACTIVE`.

Nhánh lỗi thường dùng: `PAYMENT_FAILED/EXPIRED → UNIT_AVAILABLE`, `GOODS_REVIEW_REJECTED → RESERVATION_REJECTED`, `PAYMENT_UNKNOWN → RECONCILIATION_REQUIRED`.

## 3. FLOW 2 — Gia hạn hợp đồng

### Mục tiêu

Giữ khách và gian kho đang `OCCUPIED`, chỉ cập nhật thời hạn sau khi đủ thanh toán, lịch hẹn, hợp đồng/phụ lục và xác nhận của Staff.

```mermaid
flowchart TD
    A([Rental ACTIVE]) --> B[Hệ thống thông báo sắp hết hạn]
    B --> C[Customer mở hồ sơ và chọn gói gia hạn]
    C --> D{Đủ điều kiện gia hạn?}
    D -- Không --> E[Hiển thị lý do: trả kho, tranh chấp, công nợ hoặc policy không hợp lệ]
    D -- Có --> F[Tạo quote bất biến và hiển thị tổng tiền/cọc/phần còn lại]
    F --> G[Customer chấp nhận điều khoản]
    G --> H[Customer chọn lịch hẹn ký]
    H --> I{Còn slot và lịch hợp lệ?}
    I -- Không --> H
    I -- Có --> J[Tạo payment intent và giữ slot]
    J --> K[Thanh toán cọc gia hạn qua VNPay]
    K --> L{Thanh toán đã xác thực?}
    L -- Thất bại cuối cùng --> M[Giải phóng slot/intent theo guard và thông báo thử lại]
    L -- PAID --> N[Phân bổ Ledger; trạng thái chờ ký]
    N --> O[Customer đến cơ sở theo lịch]
    O --> P[Staff đối chiếu khách, quote và hồ sơ]
    P --> Q[Thu phần còn lại bằng tiền mặt/chuyển khoản, lưu chứng từ]
    Q --> R[Staff tải hợp đồng/phụ lục đã ký]
    R --> S{Đủ bằng chứng và điều kiện hoàn tất?}
    S -- Không --> T[Giữ trạng thái chờ ký / yêu cầu bổ sung]
    T --> R
    S -- Có --> U[Staff hoàn tất gia hạn đúng một lần]
    U --> V[Cập nhật end_date của Rental; kho vẫn OCCUPIED]
    V --> W[Gửi biên lai và hợp đồng qua email]
    W --> X([End: Renewal COMPLETED])
```

### Ghi chú nghiệp vụ

- Thanh toán thành công **không tự kích hoạt gia hạn** và không tự đổi ngày kết thúc.
- Không có bước Manager phân công Staff trong flow mới; Staff có quyền tại đúng cơ sở xử lý hồ sơ.
- Khung 30 phút là slot sức chứa lịch ký, không phải hạn khách bắt buộc phải hoàn tất ký.
- Callback trễ, payment không rõ, thiếu Ledger hoặc thiếu chứng từ đi vào đối soát; không tự đánh dấu `COMPLETED`.
- Replay thao tác hoàn tất không được cộng thêm thời hạn lần hai.

## 4. FLOW 3 — Trả kho và quyết toán

### Mục tiêu

Đóng hồ sơ thuê, kiểm kê thực tế, tính số tiền hoàn/cấn trừ minh bạch và giải phóng kho an toàn.

```mermaid
flowchart TD
    A([Rental ACTIVE]) --> B[Customer gửi yêu cầu trả kho và chọn lịch]
    B --> C{Đủ điều kiện trả kho?}
    C -- Không --> D[Hiển thị lý do và hướng dẫn xử lý công nợ/tranh chấp]
    C -- Có --> E[ReturnCase PENDING_INSPECTION]
    E --> F[Customer dọn hết hàng và trả chìa/thẻ/khóa]
    F --> G[Staff kiểm kê hiện trạng tại kho]
    G --> H[Nhập ảnh, tình trạng, phí hư hại/vệ sinh và chứng từ]
    H --> I[Hệ thống tính bảng quyết toán từ các khoản đủ điều kiện]
    I --> J[Customer xem biên bản, khấu trừ và số tiền hoàn]
    J --> K{Customer đồng ý?}
    K -- Không --> L[DISPUTED: gửi khiếu nại cho Manager xử lý theo SLA]
    L --> M{Khiếu nại được chấp thuận?}
    M -- Không --> N[Giữ quyết toán cũ và thông báo lý do]
    M -- Có --> O[Cập nhật lại quyết toán có audit]
    O --> P
    K -- Có --> P[Chuyển sang chờ hoàn tiền]
    N --> P
    P --> Q[Finance/Manager chuyển tiền và tải chứng từ hoàn]
    Q --> R{Đối soát hoàn tiền thành công?}
    R -- Không --> S[Giữ REFUND_PENDING và tạo tác vụ xử lý]
    R -- Có --> T[ReturnCase COMPLETED]
    T --> U[Rental TERMINATED; thu hồi credential]
    U --> V{Kho có hư hại cần sửa?}
    V -- Có --> W[Unit MAINTENANCE và tạo MaintenanceTask]
    V -- Không --> X[Unit AVAILABLE]
    W --> Y([End])
    X --> Y
```

### Quy tắc tính và đối soát

- Số tiền hoàn là số dư các khoản đã thu và đủ điều kiện hoàn, trừ phí hợp lệ, nợ/phạt và hư hại có bằng chứng; không mặc định hoàn toàn bộ số tiền khách từng thanh toán.
- Nếu lịch trả kho được đổi, hệ thống tính lại ngày và loại quyết toán trước khi Staff kiểm kê.
- Phí đã phản ánh trong phần hoàn tiền thuê không được trừ thêm lần nữa vào cọc bảo đảm.
- Mọi quyết định tranh chấp, khấu trừ và hoàn tiền phải có người thực hiện, thời điểm, ghi chú và chứng từ.

## 5. FLOW 4 — Quản lý bảo trì và phân công Staff

### Mục tiêu

Đưa gian kho hỏng hoặc cần bảo dưỡng ra khỏi nguồn cung, giao việc cho Staff và chỉ mở lại khi có nghiệm thu.

```mermaid
flowchart TD
    A([Issue phát sinh]) --> B{Nguồn phát sinh?}
    B -- Sau trả kho --> C[Return inspection tạo đề xuất bảo trì]
    B -- Manager phát hiện --> D[Manager tạo task thủ công]
    B -- Vận hành định kỳ --> D
    C --> E[Manager chuyển Unit sang MAINTENANCE]
    D --> E
    E --> F{Unit đang OCCUPIED?}
    F -- Có --> G[Chặn; phải hoàn tất trả kho trước]
    F -- Không --> H[Chọn Staff, mức ưu tiên, hạn xử lý và lý do]
    H --> I[Task OPEN + gửi thông báo]
    I --> J[Staff nhận và bắt đầu task]
    J --> K[Task IN_PROGRESS; ghi nhận startedAt]
    K --> L[Thực hiện sửa/bảo dưỡng, tải ảnh trước/sau]
    L --> M[Staff nhập báo cáo kết quả]
    M --> N{Đạt nghiệm thu và cho phép mở lại?}
    N -- Không --> O[Giữ MAINTENANCE, tạo/tiếp tục xử lý bổ sung]
    O --> L
    N -- Có --> P[Task COMPLETED]
    P --> Q[Unit AVAILABLE]
    Q --> R([End: gian sẵn sàng nhận giữ kho])
```

### Trạng thái và quyền

- Manager: xem lịch sử, đổi `available/maintenance`, tạo task, phân công, hủy task.
- Staff: xem task trong phạm vi cơ sở, nhận/bắt đầu, hoàn tất và tải minh chứng.
- `occupied → maintenance` bị chặn nếu chưa hoàn tất thủ tục trả kho.
- Hoàn tất task chỉ tự chuyển `available` khi yêu cầu `makeUnitAvailable=true`; nếu không, gian vẫn ở `maintenance`.

## 6. FLOW 5 — Quản trị chính sách và báo cáo

### Mục tiêu

Business/Finance quản lý các chính sách được công bố; Manager chỉ đọc chính sách và xem báo cáo trong phạm vi cơ sở.

```mermaid
flowchart TD
    A([Start]) --> B{Vai trò đăng nhập?}
    B -- BUSINESS --> C[Đọc cấu hình và chính sách]
    B -- MANAGER --> D[Đọc chính sách + báo cáo cơ sở trong scope]
    B -- Không đủ quyền --> E[403 và ghi audit]
    C --> F{Thao tác?}
    F -- Sửa cấu hình --> G[Cập nhật hold, grace, late fee, deposit ratio, divisor, banner]
    F -- Quản lý gói --> H[Tạo/sửa/hủy kích hoạt gói thuê]
    F -- Xem báo cáo --> I[Báo cáo doanh thu và hiệu suất toàn hệ thống]
    D --> J[Xem summary, occupancy, hoạt động, công nợ, trả kho, bảo trì]
    G --> K[Validate policy + version + thời điểm hiệu lực]
    H --> K
    K --> L{Dữ liệu hợp lệ?}
    L -- Không --> M[Từ chối, không ghi dữ liệu]
    L -- Có --> N[Lưu thay đổi và audit log]
    N --> O[Thông báo thay đổi policy nếu cần]
    I --> P{Doanh thu đã đủ bằng chứng đối soát?}
    P -- Chưa --> Q[Hiển thị Chưa xác minh, không hiển thị 0 giả]
    P -- Có --> R[Hiển thị doanh thu thực thu, hoàn tiền và net revenue]
    J --> S([End: báo cáo theo scope])
    O --> S
    Q --> S
    R --> S
```

### Endpoint để ghi cạnh sơ đồ

| Nhóm | Endpoint chính | Quyền |
| --- | --- | --- |
| Cấu hình | `GET/PUT /api/business/config` | `policies:read/update` |
| Gói thuê | `GET/POST/PUT/DELETE /api/business/policies/packages` | Business |
| Báo cáo cơ sở | `GET /api/manager/reports/summary`, `GET /api/manager/reports/activities` | Manager theo facility scope |
| Báo cáo hệ thống | `GET /api/business/reports/revenue`, `GET /api/business/reports/performance` | Business |

### Điểm cần nhấn mạnh khi thuyết trình

- Manager không được tự sửa policy chung; Business là chủ sở hữu policy.
- `monthlyRecurringRevenue` là doanh thu dự kiến theo Rental đang active, không đồng nghĩa tiền đã thu.
- `collectedRevenue` chỉ hiển thị số khi có bằng chứng thanh toán/đối soát đủ; nếu chưa đủ thì hiển thị **Chưa xác minh**, không hiển thị 0 gây hiểu nhầm.
- Báo cáo luôn lọc theo facility scope và quyền của người xem.

## 7. Quan hệ giữa 5 flow

```mermaid
flowchart LR
    F1[Flow 1\nKhởi tạo thuê & bàn giao] --> A[(Rental ACTIVE)]
    A --> F2[Flow 2\nGia hạn]
    F2 --> A
    A --> F3[Flow 3\nTrả kho & quyết toán]
    F3 --> F4[Flow 4\nBảo trì nếu cần]
    F4 --> U[(Unit AVAILABLE)]
    U --> F1
    F5[Flow 5\nPolicy & báo cáo] -. cấu hình/giám sát .-> F1
    F5 -. cấu hình/giám sát .-> F2
    F5 -. cấu hình/giám sát .-> F3
    F5 -. cấu hình/giám sát .-> F4
```

## 8. Checklist trước khi vẽ bản cuối

- [ ] Mỗi flow có đủ Customer/System/Staff/Manager/Business/VNPay lane cần thiết.
- [ ] Mỗi nhánh lỗi đều có nơi kết thúc hoặc quay lại bước hợp lệ.
- [ ] Các điểm chờ thanh toán, chờ xác nhận, chờ đối soát được tô màu khác với thành công.
- [ ] Ghi rõ chứng từ ở các điểm: thanh toán, hợp đồng, bàn giao, kiểm kê, hoàn tiền, bảo trì.
- [ ] Không vẽ OTP ở mỗi lần đặt kho và không vẽ Manager phân công Staff như một bước bắt buộc của gia hạn.
- [ ] Ghi trạng thái cuối: `Rental ACTIVE`, `Rental TERMINATED`, `Unit AVAILABLE/MAINTENANCE`, `Policy AUDITED`.
