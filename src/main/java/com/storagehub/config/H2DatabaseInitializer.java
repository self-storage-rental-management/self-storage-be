package com.storagehub.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class H2DatabaseInitializer implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                DatabaseMetaData meta = conn.getMetaData();
                if (meta.getDatabaseProductName() != null && meta.getDatabaseProductName().toLowerCase().contains("h2")) {
                    stmt.execute("CREATE ALIAS IF NOT EXISTS BIN_TO_UUID FOR 'com.storagehub.config.H2Functions.binToUuid'");
                    stmt.execute("CREATE TABLE IF NOT EXISTS business_record_codes (resource_key varchar(120) primary key, type_code varchar(16), period varchar(8), display_code varchar(16), recorded_at timestamp)");
                    stmt.execute("CREATE TABLE IF NOT EXISTS money_receipt_codes (source_type varchar(24), source_id binary(16), receipt_code varchar(20))");
                    stmt.execute("CREATE TABLE IF NOT EXISTS registration_captchas (id varchar(36) primary key, expires_at timestamp, captcha_text varchar(16))");
                }
            } catch (Exception ignored) {
            }
        }
        return bean;
    }
}
