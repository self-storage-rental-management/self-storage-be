package com.storagehub.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** No toString: this configuration contains a server-only credential. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "storagehub.ai")
public class SupportAiProperties {
    private boolean enabled = false;
    private String provider = "gemini";
    private String apiKey = "";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    private String model = "";
    @Min(1000) @Max(30000)
    private int timeoutMs = 15000;
    @Min(128) @Max(2048)
    private int maxOutputTokens = 768;
    @DecimalMin("0.0") @DecimalMax("1.0")
    private double temperature = 0.2;
}
