package com.storagehub.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    @Bean
    CommandLineRunner seedSystemSettings() {
        return args -> definitions().forEach(this::seedOrRepair);
    }

    private void seedOrRepair(Definition definition) {
        var existing = repository.findBySettingKey(definition.key());
        if (existing.isPresent()) {
            SystemSetting setting = existing.get();
            boolean changed = false;
            if (!isValidStoredValue(setting, definition)) {
                setting.setValue(definition.value());
                changed = true;
            }
            if (!definition.group().equals(setting.getGroupName())) {
                setting.setGroupName(definition.group());
                changed = true;
            }
            if (!java.util.Objects.equals(definition.description(), setting.getDescription())) {
                setting.setDescription(definition.description());
                changed = true;
            }
            if (!definition.label().equals(setting.getLabel())) {
                setting.setLabel(definition.label());
                changed = true;
            }
            if (!definition.type().equals(setting.getSettingType())) {
                setting.setSettingType(definition.type());
                changed = true;
            }
            if (!java.util.Objects.equals(definition.optionsJson(), setting.getOptionsJson())) {
                setting.setOptionsJson(definition.optionsJson());
                changed = true;
            }
            if (changed) repository.save(setting);
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

    private boolean isValidStoredValue(SystemSetting setting, Definition definition) {
        try {
            JsonNode value = objectMapper.readTree(setting.getValue());
            if (value == null) return false;
            return switch (definition.type()) {
                case "text" -> value.isTextual();
                case "select" -> value.isTextual() && readOptions(definition).contains(value.textValue());
                case "number" -> value.isNumber();
                case "toggle" -> value.isBoolean();
                default -> false;
            };
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> readOptions(Definition definition) {
        if (definition.optionsJson() == null || definition.optionsJson().isBlank()) return List.of();
        try {
            return List.of(objectMapper.readValue(definition.optionsJson(), String[].class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Definition> definitions() {
        return List.of(
            new Definition("businessName", "Facility & Business Profile", "General organization parameters and primary contact channels.", "Company / Facility Name", "text", "\"StorageHub Vietnam\"", null),
            new Definition("contactEmail", "Facility & Business Profile", null, "Primary Support Email", "text", "\"support@storagehub.demo\"", null),
            new Definition("hotline", "Facility & Business Profile", null, "Customer Hotline", "text", "\"+84 28 3822 8888\"", null),
            new Definition("timezone", "Facility & Business Profile", null, "Facility Timezone", "select", "\"GMT+7 (Asia/Ho_Chi_Minh)\"", "[\"GMT+7 (Asia/Ho_Chi_Minh)\",\"GMT+8 (Asia/Singapore)\",\"GMT+0 (UTC)\"]"),
            new Definition("autoInvoiceDays", "Billing & Invoicing Rules", null, "Advance Invoice Generation (Days)", "select", "\"7 days before due date\"", "[\"3 days before due date\",\"7 days before due date\",\"14 days before due date\",\"30 days before due date\"]"),
            new Definition("autoProrate", "Billing & Invoicing Rules", null, "Prorate First Month Rent on Move-in", "toggle", "true", null),
            new Definition("require2FA", "Security & Access Controls", "Hardware controller security, access lockout, and authentication requirements.", "Enforce Two-Factor Authentication (2FA) for Staff", "toggle", "true", null),
            new Definition("lockoutAttempts", "Security & Access Controls", null, "Failed Login Lockout Threshold", "select", "\"3 failed attempts\"", "[\"3 failed attempts\",\"5 failed attempts\",\"10 failed attempts\"]"),
            new Definition("sessionTimeout", "Security & Access Controls", null, "Admin Session Inactivity Timeout", "select", "\"30 minutes\"", "[\"15 minutes\",\"30 minutes\",\"60 minutes\",\"4 hours\"]"),
            new Definition(com.storagehub.service.WorkflowBusinessCalendar.KEY, "Automated Notifications & Webhooks",
                "Định dạng Ngày làm việc|Giờ mở|Giờ đóng|Ngày nghỉ. Ví dụ MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY|08:00|17:00|2027-01-01. Dùng giờ Việt Nam.",
                "Lịch làm việc SLA", "text", "\"" + com.storagehub.service.WorkflowBusinessCalendar.DEFAULT + "\"", null),
            new Definition("maintenanceMode", "Maintenance & Service Mode", "Tạm dừng tiếp nhận đơn đặt kho mới trong thời gian bảo trì.", "Chế độ bảo trì hệ thống", "toggle", "false", null),
            new Definition("bannerNotice", "Maintenance & Service Mode", "Thông báo hiển thị công khai trên trang chủ khi cơ sở cần thông tin tới khách hàng.", "Thông báo công khai cho khách hàng", "text", "\"\"", null)
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
