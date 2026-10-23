# Backend contracts

Mỗi flow có một contract chính. Khi bổ sung hoặc sửa API, cập nhật trực tiếp tài liệu tương ứng, không tạo contract phụ cho cùng flow.

| Flow | Contract chính |
|---|---|
| Quy ước API dùng chung | [BE-00_API_CONTRACT.md](BE-00_API_CONTRACT.md) |
| Xác thực và tài khoản | [AUTH_API.md](AUTH_API.md) |
| Danh mục cơ sở/kho | [CUSTOMER_CATALOG_API.md](CUSTOMER_CATALOG_API.md) |
| Đặt giữ kho | [BOOKING_API_CONTRACT.md](BOOKING_API_CONTRACT.md) |
| Thanh toán và VNPay | [PAYMENT_API_CONTRACT.md](PAYMENT_API_CONTRACT.md) |
| Nhận kho và bàn giao | [CHECKIN_HANDOVER_API_CONTRACT.md](CHECKIN_HANDOVER_API_CONTRACT.md) |
| Giải phóng kho của đơn đã hủy | [CANCELLED_RESERVATION_UNIT_RELEASE_API_CONTRACT.md](CANCELLED_RESERVATION_UNIT_RELEASE_API_CONTRACT.md) |
| Hồ sơ thuê | [RENTAL_API_CONTRACT.md](RENTAL_API_CONTRACT.md) |
| Gia hạn: yêu cầu, thanh toán, ký và hoàn tất | [RENEWAL_API_CONTRACT.md](RENEWAL_API_CONTRACT.md) |
| Quá hạn | [OVERDUE_API_CONTRACT.md](OVERDUE_API_CONTRACT.md) |
| Trả kho và quyết toán | [RETURN_SETTLEMENT_API_CONTRACT.md](RETURN_SETTLEMENT_API_CONTRACT.md) |
| Hỗ trợ khách hàng | [SUPPORT_API_CONTRACT.md](SUPPORT_API_CONTRACT.md) |
| Bảo trì | [MAINTENANCE_API_CONTRACT.md](MAINTENANCE_API_CONTRACT.md) |
| Cấu hình và báo cáo | [CONFIGURATION_REPORTING_API_CONTRACT.md](CONFIGURATION_REPORTING_API_CONTRACT.md) |
| Tích hợp Ledger và Communication | [LEDGER_COMMUNICATION_INTEGRATION_API_CONTRACT.md](LEDGER_COMMUNICATION_INTEGRATION_API_CONTRACT.md) |

`DATABASE_CHANGELOG.md`, `PAYMENT_GATEWAY_MIGRATION.md`, `RENTAL_SUPPORT_SCHEMA_ROLLOUT.md` và `email-api-changes.md` là lịch sử hoặc hướng dẫn triển khai, không thay thế contract API. File SQL và cấu hình mẫu không phải contract riêng.

## Kịch bản demo và kiểm thử thủ công

- [Happy path 4 flow chính](HAPPY_PATH_ALL_FLOWS.md): đặt kho và nhận kho, trả kho, gia hạn, hỗ trợ khách hàng; gồm điều kiện, thao tác và kết quả mong đợi.
- [Gia hạn, quá hạn và hỗ trợ — chi tiết](HAPPY_PATH_RENEWAL_OVERDUE_SUPPORT.md): thao tác, kết quả từng bước và phiếu ghi nhận cho ba flow này.

Các kịch bản là hướng dẫn chạy, không thay thế contract và không phải bằng chứng đã kiểm thử thành công trên môi trường triển khai.

## Kế hoạch phát triển

- [Chat hỗ trợ AI và bàn giao Staff](SUPPORT_AI_CHAT_PLAN.md): phạm vi, hạng mục thêm/sửa/xóa, API và dữ liệu đề xuất, kiểm thử và công cụ hỗ trợ.
