package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import java.time.Year;
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
            variables.forEach(context::setVariable);
        }

        String templatePath = templateName.startsWith("email/") ? templateName : "email/" + templateName;
        return templateEngine.process(templatePath, context);
    }
}
