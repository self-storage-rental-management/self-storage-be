package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.storagehub.domain.repo.ReservationRepository;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ReservationPublicCodeTests {
    @Test
    void codesAreRandomUniqueAndFitTheDatabaseColumn() {
        var codes = new HashSet<String>();
        for (int i = 0; i < 10000; i++) {
            String code = ReservationCreationService.generateReservationCode();
            assertThat(code).matches("RSV-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}");
            assertThat(codes.add(code)).isTrue();
        }
    }

    @Test
    void retriesWhenGeneratedCodeAlreadyExists() {
        var repository = mock(ReservationRepository.class);
        when(repository.existsByReservationCode("RSV-K7M9-X4P2-Q8TN")).thenReturn(true);
        var candidates = java.util.List.of("RSV-K7M9-X4P2-Q8TN", "RSV-H8RT-9K2W-7N5A").iterator();
        assertThat(ReservationCreationService.uniqueReservationCode(repository, candidates::next))
            .isEqualTo("RSV-H8RT-9K2W-7N5A");
        verify(repository).existsByReservationCode("RSV-K7M9-X4P2-Q8TN");
        verify(repository).existsByReservationCode("RSV-H8RT-9K2W-7N5A");
    }

    @Test
    void neverReturnsDuplicateCodeWhenRetryLimitIsReached() {
        var repository = mock(ReservationRepository.class);
        when(repository.existsByReservationCode(anyString())).thenReturn(true);
        assertThatThrownBy(() -> ReservationCreationService.uniqueReservationCode(repository,
            () -> "RSV-K7M9-X4P2-Q8TN"))
            .isInstanceOf(com.storagehub.common.api.ApiException.class);
        verify(repository, times(20)).existsByReservationCode(anyString());
    }
}
