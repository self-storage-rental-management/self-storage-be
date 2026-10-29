package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.storagehub.api.reservation.GoodsCatalogEventsController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class GoodsCatalogEventsTests {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean GoodsCatalogEvents events() { return new GoodsCatalogEvents(); }
    }

    @Test
    void streamsChangesOnlyAfterCommitAndSkipsRollback() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var events = context.getBean(GoodsCatalogEvents.class);
            var mvc = MockMvcBuilders.standaloneSetup(new GoodsCatalogEventsController(events)).build();
            var result = mvc.perform(get("/api/goods-catalog/events"))
                .andExpect(request().asyncStarted()).andReturn();
            assertThat(result.getResponse().getContentAsString()).contains("event:ready");
            assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
            transaction(context, TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("event:catalog-changed");
            transaction(context, TransactionSynchronization.STATUS_COMMITTED);
            assertThat(result.getResponse().getContentAsString()).contains("event:catalog-changed");
            result.getRequest().getAsyncContext().complete();
        }
    }

    private void transaction(AnnotationConfigApplicationContext context, int status) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            context.publishEvent(new GoodsCatalogEvents.Changed());
            TransactionSynchronizationUtils.triggerAfterCompletion(status);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
