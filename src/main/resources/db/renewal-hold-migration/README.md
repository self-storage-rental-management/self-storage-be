# Extension hold rollout — opt-in, chưa áp dụng DB thật

Migration V2026101104 tạo duy nhất renewal_extension_holds; 4 FK tới Renewal/Rental/Unit/Facility, unique renewal_id, index unit/state/interval và CHECK dates/state/expiry.

Không nằm trong db/migration mặc định. Không thay migration đã chạy, không có seed/backfill/reset. Persistence dùng JDBC nên không thêm Entity vào schema validate của triển khai đang tắt.

Chỉ rollout sau khi có implementation RenewalSharedWriterProtocol thực: Booking, Assignment, Return, Maintenance, Recovery và Payment cùng dùng khóa/thứ tự và kiểm tra hold. Không dùng ready=true bằng cấu hình.

Sau khi kiểm thử nâng cấp MySQL test và xác nhận giao thức, thêm location classpath:db/renewal-hold-migration vào cấu hình Flyway triển khai phù hợp với bộ chọn lịch sử migration hiện tại. Không sửa checksum hoặc bật ddl-auto=create/update. Hiện chỉ SQL trên H2 test cô lập đã được chạy; không coi đó là xác minh rollout MySQL/TiDB.

Hai flag storagehub.integration.renewal-extension-holds.enabled=true và storagehub.integration.renewal-operational-checks.enabled=true chỉ đăng ký adapter; không thay thế các nguồn Recovery/calendar/financial/approval lifecycle hoặc protocol.
