package com.storagehub.service.email;

import com.storagehub.common.text.TextEncodingRepair;
import com.storagehub.config.EmailProperties;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final ITemplateEngine templateEngine;
    private final EmailProperties emailProperties;

    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context(Locale.forLanguageTag("vi"));

        // Global defaults from configuration
        context.setVariable("supportEmail", emailProperties.getSupportEmail());
        context.setVariable("companyAddress", emailProperties.getCompanyAddress());
        context.setVariable("year", Year.now().getValue());
        if (emailProperties.getLogoUrl() != null && !emailProperties.getLogoUrl().isBlank()) {
            context.setVariable("logoUrl", emailProperties.getLogoUrl());
        }

        // Apply provided variables
        if (variables != null) {
            variables.forEach((key, value) -> context.setVariable(key, sanitizeTemplateValue(value)));
        }

        String templatePath = templateName.startsWith("email/") ? templateName : "email/" + templateName;
        return templateEngine.process(templatePath, context);
    }

    private Object sanitizeTemplateValue(Object value) {
        if (value instanceof String str) {
            return TextEncodingRepair.repairUtf8Mojibake(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> repaired = new LinkedHashMap<>();
            map.forEach((k, v) -> repaired.put(
                k instanceof String ks ? TextEncodingRepair.repairUtf8Mojibake(ks) : k,
                sanitizeTemplateValue(v)
            ));
            return repaired;
        }
        return value;
    }
}
