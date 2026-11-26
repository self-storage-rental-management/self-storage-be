package com.storagehub.config;

import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ProcessingGuard;
import com.storagehub.service.ProcessingLeaseService;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ProcessingLeaseConfiguration {
    @Bean
    @org.springframework.context.annotation.Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    public Advisor processingLeaseAdvisor(ObjectProvider<ProcessingLeaseService> leases) {
        MethodInterceptor interceptor = invocation -> {
            ProcessingGuard guard = invocation.getMethod().getAnnotation(ProcessingGuard.class);
            return leases.getObject().command((ActorPrincipal) invocation.getArguments()[0], guard.value(),
                (UUID) invocation.getArguments()[1], invocation::proceed);
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
            AnnotationMatchingPointcut.forMethodAnnotation(ProcessingGuard.class), interceptor);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }
}
