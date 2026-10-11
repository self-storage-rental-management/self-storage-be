package com.storagehub.service.communication;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class CommunicationSchemaGuard implements InitializingBean {
    private final JdbcTemplate jdbc;public CommunicationSchemaGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void afterPropertiesSet(){jdbc.queryForList("select id,recipient_id,resource_id,kind,resolution_event_id,policy_ref,policy_version,content,key_hash,payload_hash,status,attempts,retry_at_ms,notification_id,acknowledged_at from notification_outbox where 1=0");}
}
