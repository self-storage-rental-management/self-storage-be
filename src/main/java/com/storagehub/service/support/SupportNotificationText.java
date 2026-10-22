package com.storagehub.service.support;

/** Vietnamese system titles, including notifications saved by earlier releases. */
public final class SupportNotificationText {
    private SupportNotificationText() {}

    public static String title(String value) {
        if(value==null)return null;
        return switch(value) {
            case "New Support ticket" -> "Có yêu cầu hỗ trợ mới";
            case "Support assigned", "Support reassigned" -> "Yêu cầu hỗ trợ đã được phân công";
            case "Staff accepted Support", "Staff claimed Support" -> "Nhân viên đã tiếp nhận yêu cầu hỗ trợ";
            case "Support ticket received" -> "Yêu cầu hỗ trợ của bạn đã được tiếp nhận";
            case "Customer replied to Support" -> "Khách hàng đã gửi tin nhắn hỗ trợ";
            case "Staff replied to Support" -> "Nhân viên đã phản hồi yêu cầu hỗ trợ";
            case "Manager replied to Support" -> "Quản lý đã gửi tin nhắn hỗ trợ";
            case "Support needs your information" -> "Vui lòng bổ sung thông tin cho yêu cầu hỗ trợ";
            case "Support needs customer information" -> "Yêu cầu hỗ trợ đang chờ khách hàng bổ sung";
            case "Support resolved; please review" -> "Yêu cầu hỗ trợ đã xử lý, chờ bạn xác nhận";
            case "Support resolved" -> "Nhân viên đã báo xử lý xong yêu cầu hỗ trợ";
            case "Customer closed Support" -> "Khách hàng đã xác nhận kết quả hỗ trợ";
            case "Customer reopened Support", "Support reopened" -> "Khách hàng yêu cầu xử lý lại hỗ trợ";
            case "Support automatically closed after review period" -> "Yêu cầu hỗ trợ đã tự động đóng sau thời gian xác nhận";
            default -> value;
        };
    }
}
