package com.storagehub.domain.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class UnitAssignmentQueryTests {

    @Autowired private ReservationRepository reservationRepository;
    @Autowired private StorageUnitRepository storageUnitRepository;

    @Test
    void assignmentCandidateQueryExecutes() {
        var result = reservationRepository.findUnitAssignmentCandidates(
            null, null, null, false, List.of(new UUID(0, 0)), PageRequest.of(0, 20)
        );

        assertThat(result).isEmpty();
    }

    @Test
    void assignableUnitQueryExecutes() {
        var result = storageUnitRepository.findAssignableUnits(
            UUID.randomUUID(), UUID.randomUUID(), null, PageRequest.of(0, 20)
        );

        assertThat(result).isEmpty();
    }

    @Test
    void checkInWorkQueryExecutes() {
        var result = reservationRepository.findCheckInWork(
            null, null, false, false, null, null, null,
            false, List.of(new UUID(0, 0)), PageRequest.of(0, 20)
        );

        assertThat(result).isEmpty();
    }
}
