package com.storagehub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mail")
public class EmailProperties {

    private boolean enabled = false;
    private String from = "no-reply@storagehub.local";
    private String supportEmail = "support@storagehub.vn";
    private String companyAddress = "Tòa nhà StorageHub, Khu Công Nghệ Cao, TP.HCM";
    private String logoUrl = "";
    private String frontendBaseUrl = "http://localhost:5173";
    private String loginUrl = "http://localhost:5173/login";
    private String securityUrl = "http://localhost:5173/profile/security";
    private String accountUrl = "http://localhost:5173/profile";
}
