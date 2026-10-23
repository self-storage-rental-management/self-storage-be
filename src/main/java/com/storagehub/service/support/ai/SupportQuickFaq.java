package com.storagehub.service.support.ai;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Exact reviewed generic questions only: never guess intent from broad keywords. */
@Component
public class SupportQuickFaq {
    private record Entry(String question, String answer) {}
    private static final List<Entry> ENTRIES = List.of(
        new Entry("Chọn kích thước kho phù hợp", "Vào **Tìm gian kho trống**, chọn cơ sở và xem thông số cỡ kho.\n\nKhai báo kích thước, số lượng và đặc điểm đồ đạc để hệ thống kiểm tra khả năng lưu trữ."),
        new Entry("Làm sao để gia hạn thuê kho?", "Vào **Hồ sơ thuê của tôi**, chọn hồ sơ cần gia hạn.\n\nLàm theo các bước đang mở: xem báo giá, chọn gói, lịch ký và thanh toán. Thanh toán thành công chưa có nghĩa đã hoàn tất ký gia hạn."),
        new Entry("Thanh toán và xem giao dịch ở đâu?", "Xem giao dịch tại **Lịch sử thanh toán**.\n\nĐể thanh toán, mở đúng **đơn đặt kho** hoặc **yêu cầu gia hạn**, rồi chọn nút thanh toán đang khả dụng."),
        new Entry("Trả kho và hoàn tiền cọc thế nào?", "Vào **Hồ sơ thuê của tôi**, chọn hồ sơ và **Yêu cầu trả kho** nếu hệ thống cho phép.\n\nNhân viên sẽ kiểm tra và quyết toán. Số tiền, thời gian hoàn cọc phụ thuộc kết quả xử lý thực tế."),
        new Entry("Quên hoặc muốn đổi mật khẩu", "Dùng **Quên mật khẩu** trên màn hình đăng nhập, hoặc chức năng đổi mật khẩu trong **hồ sơ cá nhân**.\n\nKhông gửi mật khẩu hoặc OTP qua chat."),
        new Entry("Mã truy cập không hoạt động", "Chọn tab **Nhân viên**, xác nhận cơ sở hoặc hồ sơ thuê để được kiểm tra.\n\nKhông gửi mã truy cập trong chat. Trợ lý không thể cấp hoặc đặt lại mã."),
        new Entry("Cổng hoặc cửa kho gặp sự cố", "Chọn tab **Nhân viên**, xác nhận đúng cơ sở và mô tả sự cố cổng/cửa kho.\n\nCần nhân viên kiểm tra trực tiếp; câu hỏi này chưa tự tạo yêu cầu hỗ trợ."),
        new Entry("Tôi muốn đổi sang kho khác", "Tham khảo kho tại **Tìm gian kho trống**.\n\nNếu muốn chuyển kho đang thuê, chọn tab **Nhân viên** để kiểm tra điều kiện. Trợ lý không tự chuyển hợp đồng hoặc tiền cọc."),
        new Entry("Tôi cần kiểm tra khoản quá hạn", "Mở **Hồ sơ thuê của tôi** để xem tình trạng quá hạn, khoản thu và thao tác được phép.\n\nNếu cần đối soát số tiền hoặc thời hạn, chọn tab **Nhân viên** và liên kết đúng hồ sơ."),
        new Entry("Báo hư hỏng hoặc sự cố tại kho", "Chọn tab **Nhân viên**, xác nhận cơ sở/hồ sơ rồi mô tả sự cố; có thể đính kèm hình ảnh.\n\nNếu có nguy hiểm trực tiếp, ưu tiên rời khu vực nguy hiểm và liên hệ nhân sự tại cơ sở.")
    );

    public Optional<String> answer(String question) {
        String normalized = normalize(question);
        return ENTRIES.stream().filter(entry -> normalize(entry.question()).equals(normalized))
            .map(Entry::answer).findFirst();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ").trim();
    }
}
