package com.storagehub.domain.repo;

import com.storagehub.domain.model.PaymentWebhookEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {
    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
}
