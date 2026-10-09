package com.storagehub.domain.repo;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class UnitAssignmentQueryTests {

    @Autowired private ReservationRepository reservationRepository;
    @Autowired private StorageUnitRepository storageUnitRepository;
    @Autowired private CheckInRepository checkInRepository;

    @Test
    void reservationForUpdateQueryExecutesWithWriteLockContract() throws NoSuchMethodException {
        var method = ReservationRepository.class.getMethod("findByIdForUpdate", UUID.class);

        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(reservationRepository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    void checkInForUpdateQueryExecutesWithWriteLockContract() throws NoSuchMethodException {
        var method = CheckInRepository.class.getMethod("findByIdForUpdate", UUID.class);

        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(checkInRepository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }

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
