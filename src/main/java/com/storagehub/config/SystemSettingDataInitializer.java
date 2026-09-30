package com.storagehub.config;

import com.storagehub.domain.model.SystemSetting;
import com.storagehub.domain.repo.SystemSettingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SystemSettingDataInitializer {

    private final SystemSettingRepository repository;

    @Bean
    CommandLineRunner seedSystemSettings() {
        return args -> definitions().forEach(this::seedIfMissing);
    }

    private void seedIfMissing(Definition definition) {
        if (repository.findBySettingKey(definition.key()).isPresent()) {
            return;
        }
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(definition.key());
        setting.setGroupName(definition.group());
        setting.setDescription(definition.description());
        setting.setLabel(definition.label());
        setting.setSettingType(definition.type());
        setting.setValue(definition.value());
        setting.setOptionsJson(definition.optionsJson());
        repository.save(setting);
    }

    private List<Definition> definitions() {
        return List.of(
            new Definition("businessName", "Facility & Business Profile", "General organization parameters and primary contact channels.", "Company / Facility Name", "text", "\"StorageHub Vietnam\"", null),
            new Definition("contactEmail", "Facility & Business Profile", null, "Primary Support Email", "text", "\"support@storagehub.demo\"", null),
            new Definition("hotline", "Facility & Business Profile", null, "Customer Hotline", "text", "\"+84 28 3822 8888\"", null),
            new Definition("currency", "Facility & Business Profile", null, "Operating Currency", "select", "\"VND (₫)\"", "[\"VND (₫)\"]"),
            new Definition("timezone", "Facility & Business Profile", null, "Facility Timezone", "select", "\"GMT+7 (Asia/Ho_Chi_Minh)\"", "[\"GMT+7 (Asia/Ho_Chi_Minh)\",\"GMT+8 (Asia/Singapore)\",\"GMT+0 (UTC)\"]"),
            new Definition("gracePeriod", "Billing & Invoicing Rules", "Automated invoice generation, grace periods, and late penalty triggers.", "Late Fee Grace Period (Days)", "number", "5", null),
            new Definition("lateFeeAmount", "Billing & Invoicing Rules", null, "Fixed Late Fee Amount (VND)", "number", "650000", null),
            new Definition("autoInvoiceDays", "Billing & Invoicing Rules", null, "Advance Invoice Generation (Days)", "select", "\"7 days before due date\"", "[\"3 days before due date\",\"7 days before due date\",\"14 days before due date\",\"30 days before due date\"]"),
            new Definition("autoProrate", "Billing & Invoicing Rules", null, "Prorate First Month Rent on Move-in", "toggle", "true", null),
            new Definition("require2FA", "Security & Access Controls", "Hardware controller security, access lockout, and authentication requirements.", "Enforce Two-Factor Authentication (2FA) for Staff", "toggle", "true", null),
            new Definition("pinRotation", "Security & Access Controls", null, "Gate PIN Auto-Rotation Interval", "select", "\"90 days\"", "[\"30 days\",\"60 days\",\"90 days\",\"Never (Manual)\"]"),
            new Definition("lockoutAttempts", "Security & Access Controls", null, "Failed PIN Lockout Threshold", "select", "\"3 failed attempts\"", "[\"3 failed attempts\",\"5 failed attempts\",\"10 failed attempts\"]"),
            new Definition("sessionTimeout", "Security & Access Controls", null, "Admin Session Inactivity Timeout", "select", "\"30 minutes\"", "[\"15 minutes\",\"30 minutes\",\"60 minutes\",\"4 hours\"]"),
            new Definition("emailAlerts", "Automated Notifications & Webhooks", "Direct SMS and email dispatch configurations for operational alerts.", "Send Overdue Reminder Emails", "toggle", "true", null),
            new Definition("smsGateAlerts", "Automated Notifications & Webhooks", null, "Send SMS on After-Hours Gate Access", "toggle", "false", null),
            new Definition("slackWebhook", "Automated Notifications & Webhooks", null, "Operational Incident Slack Webhook", "text", "\"https://hooks.slack.com/services/T00/B00/XXXX\"", null),
            new Definition("dailyDigest", "Automated Notifications & Webhooks", null, "Daily Executive Performance Digest to Managers", "toggle", "true", null),
            new Definition("maintenanceMode", "Maintenance & Service Mode", "Emergency controls and maintenance banners.", "Maintenance Mode (Block New Bookings)", "toggle", "false", null),
            new Definition("bannerNotice", "Maintenance & Service Mode", null, "Public Facility Announcement Banner", "text", "\"Routine fire alarm testing scheduled this Sunday 9:00 AM - 11:00 AM.\"", null)
        );
    }

    private record Definition(
        String key,
        String group,
        String description,
        String label,
        String type,
        String value,
        String optionsJson
    ) {
    }
}
