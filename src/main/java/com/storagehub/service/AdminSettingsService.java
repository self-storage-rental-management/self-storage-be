package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.admin.AdminSettingResponse;
import com.storagehub.api.admin.AdminUpdateSettingRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.SystemSetting;
import com.storagehub.domain.repo.SystemSettingRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    private final SystemSettingRepository repository;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<AdminSettingResponse> list(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.MANAGE_SETTINGS);
        return repository.findAll().stream()
            .sorted(Comparator.comparing(SystemSetting::getGroupName).thenComparing(SystemSetting::getId))
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public AdminSettingResponse update(ActorPrincipal actor, String key, AdminUpdateSettingRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_SETTINGS);
        SystemSetting setting = repository.findBySettingKey(key)
            .orElseThrow(() -> ApiExceptions.notFound("Setting was not found"));
        validateValue(setting, request.value());
        AdminSettingResponse before = toResponse(setting);
        setting.setValue(request.value().toString());
        SystemSetting saved = repository.saveAndFlush(setting);
        AdminSettingResponse after = toResponse(saved);
        auditLogService.recordMutation(
            "ADMIN_SETTING_UPDATED", "SystemSetting", saved.getId(), null, before, after
        );
        return after;
    }

    private void validateValue(SystemSetting setting, JsonNode value) {
        if (value == null || value.isNull()) {
            throw ApiExceptions.validation("Setting value must not be null", null);
        }
        switch (setting.getSettingType()) {
            case "toggle" -> {
                if (!value.isBoolean()) {
                    throw ApiExceptions.validation("Setting value must be boolean", setting.getSettingKey());
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    throw ApiExceptions.validation("Setting value must be numeric", setting.getSettingKey());
                }
            }
            case "text", "select" -> {
                if (!value.isTextual()) {
                    throw ApiExceptions.validation("Setting value must be text", setting.getSettingKey());
                }
                if ("select".equals(setting.getSettingType()) && !readOptions(setting).contains(value.textValue())) {
                    throw ApiExceptions.validation("Setting value is not one of the supported options", setting.getSettingKey());
                }
            }
            default -> throw ApiExceptions.conflict("Setting type is not supported: " + setting.getSettingType());
        }
    }

    private AdminSettingResponse toResponse(SystemSetting setting) {
        try {
            return new AdminSettingResponse(
                setting.getSettingKey(),
                setting.getGroupName(),
                setting.getDescription(),
                setting.getLabel(),
                setting.getSettingType(),
                objectMapper.readTree(setting.getValue()),
                readOptions(setting),
                setting.getUpdatedAt()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored setting value is invalid", exception);
        }
    }

    private List<String> readOptions(SystemSetting setting) {
        if (setting.getOptionsJson() == null || setting.getOptionsJson().isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(setting.getOptionsJson(), String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored setting options are invalid", exception);
        }
    }
}
