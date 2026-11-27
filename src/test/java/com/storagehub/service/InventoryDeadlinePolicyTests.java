package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Rental;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InventoryDeadlinePolicyTests {
    private final Instant deadline = Instant.parse("2026-11-26T12:20:00Z");

    @Test
    void expiryUsesThe72HourCompletionDeadlineEvenWhenArrivalDeadlineIsLater() {
        var rental = new Rental();
        rental.setInspectionClockStartedAt(deadline.minus(Duration.ofHours(72)));
        rental.setInspectionCompletionDeadlineAt(deadline);
        rental.setInventoryFinalDeadlineAt(deadline.plus(Duration.ofDays(2)));
        assertThatCode(() -> InventoryDeadlinePolicy.requireInventoryOpen(rental, deadline)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InventoryDeadlinePolicy.requireInventoryOpen(rental, deadline.plusNanos(1)))
            .isInstanceOf(ApiException.class).hasMessageContaining("72 giờ");
    }

    @Test
    void legacyFirstVerificationStartsTheCompletionClockWhenArrivalClockWasNotPersisted() {
        var rental = new Rental();
        rental.setFirstIdentityVerificationAt(deadline.minus(Duration.ofHours(72)));
        rental.setInventoryFinalDeadlineAt(deadline.plus(Duration.ofDays(2)));
        assertThat(InventoryDeadlinePolicy.completionDeadline(rental)).isEqualTo(deadline);
        assertThatThrownBy(() -> InventoryDeadlinePolicy.requireInventoryOpen(rental, deadline.plusSeconds(1)))
            .isInstanceOf(ApiException.class).hasMessageContaining("72 giờ");
    }

    @Test
    void recoveryAlwaysPreventsInspectionEvenIfTheCheckInStatusHasNotChanged() {
        var rental = new Rental();
        rental.setInventoryEscalatedAt(deadline.minusSeconds(5));
        rental.setInspectionCompletionDeadlineAt(deadline.plusSeconds(3600));
        assertThat(InventoryDeadlinePolicy.restriction(rental, deadline)).isEqualTo(InventoryDeadlinePolicy.Restriction.RECOVERY);
        assertThatThrownBy(() -> InventoryDeadlinePolicy.requireInventoryOpen(rental, deadline))
            .isInstanceOf(ApiException.class).hasMessageContaining("thu hồi");
    }
}
