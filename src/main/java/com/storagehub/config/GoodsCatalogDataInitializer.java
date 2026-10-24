package com.storagehub.config;

import com.storagehub.service.GoodsCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@RequiredArgsConstructor
public class GoodsCatalogDataInitializer {
    private final GoodsCatalogService catalogService;

    @Bean
    @Order(15)
    CommandLineRunner initializeGoodsCatalog() {
        return args -> catalogService.seedDefaults();
    }
}
