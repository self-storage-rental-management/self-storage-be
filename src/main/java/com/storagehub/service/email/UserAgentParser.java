package com.storagehub.service.email;

public final class UserAgentParser {

    public static final String UNKNOWN_FINGERPRINT = "UNKNOWN|UNKNOWN";

    private UserAgentParser() {
    }

    public record ClientInfo(String device, String browser, String summary, String fingerprint) {
    }

    public static ClientInfo parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ClientInfo("Không xác định", "Không xác định", "Không xác định", UNKNOWN_FINGERPRINT);
        }

        String ua = userAgent.toLowerCase();

        String device;
        if (ua.contains("windows")) {
            device = "Windows PC";
        } else if (ua.contains("iphone")) {
            device = "iPhone";
        } else if (ua.contains("ipad")) {
            device = "iPad";
        } else if (ua.contains("macintosh") || ua.contains("mac os")) {
            device = "Mac";
        } else if (ua.contains("android")) {
            device = "Thiết bị Android";
        } else if (ua.contains("linux")) {
            device = "Linux PC";
        } else {
            device = "Không xác định";
        }

        String browser;
        if (ua.contains("edg/") || ua.contains("edge/")) {
            browser = "Edge";
        } else if (ua.contains("opr/") || ua.contains("opera/")) {
            browser = "Opera";
        } else if (ua.contains("chrome/") || ua.contains("crios/")) {
            browser = "Chrome";
        } else if (ua.contains("firefox/") || ua.contains("fxios/")) {
            browser = "Firefox";
        } else if (ua.contains("safari/") && !ua.contains("chrome/")) {
            browser = "Safari";
        } else {
            browser = "Không xác định";
        }

        String summary;
        if (!"Không xác định".equals(browser) && !"Không xác định".equals(device)) {
            summary = browser + " trên " + device;
        } else if (!"Không xác định".equals(browser)) {
            summary = browser;
        } else if (!"Không xác định".equals(device)) {
            summary = device;
        } else {
            summary = "Không xác định";
        }

        String fingerprint;
        if ("Không xác định".equals(device) && "Không xác định".equals(browser)) {
            fingerprint = UNKNOWN_FINGERPRINT;
        } else {
            fingerprint = device + "|" + browser;
        }

        return new ClientInfo(device, browser, summary, fingerprint);
    }
}
