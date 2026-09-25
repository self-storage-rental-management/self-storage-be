package com.storagehub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.files")
public class FileProperties {
    private String storagePath = "./uploads";
    private long maxSizeBytes = 10 * 1024 * 1024;
}
