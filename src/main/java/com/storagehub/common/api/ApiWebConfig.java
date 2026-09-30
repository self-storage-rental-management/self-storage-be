package com.storagehub.common.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

@Configuration
public class ApiWebConfig {

    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableDefaults() {
        return resolver -> {
            resolver.setPageParameterName("page");
            resolver.setSizeParameterName("pageSize");
            resolver.setMaxPageSize(100);
            resolver.setOneIndexedParameters(false);
        };
    }
}
