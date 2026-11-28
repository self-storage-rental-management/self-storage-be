package com.storagehub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.AccessCredential;
import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.AccessCredentialType;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.LockInspectionResult;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.AccessCredentialRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

class AccessCredentialLockReturnTests {
    private final FacilityRepository facilities = mock(FacilityRepository.class);
    private final AccessCredentialRepository credentials = mock(AccessCredentialRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccessCredentialService service = new AccessCredentialService(
        facilities, credentials, null, null, null, null, null, users,
        mock(AdminAuthorizationService.class), mock(FacilityScopeService.class), mock(AuditLogService.class), null
    );
    private final ActorPrincipal staff = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(RoleCode.STAFF), Set.of(), Map.of());
    private final Rental rental = mock(Rental.class);
    private final Facility facility = mock(Facility.class);
    private final AccessCredential lock = new AccessCredential();

    @BeforeEach
    void setUp() {
        when(facility.getId()).thenReturn(UUID.randomUUID());
        when(facilities.findByIdForUpdate(facility.getId())).thenReturn(Optional.of(facility));
        when(rental.getId()).thenReturn(UUID.randomUUID());
        when(rental.getFacility()).thenReturn(facility);
        var operator = new User();
        when(users.findById(staff.userId())).thenReturn(Optional.of(operator));
        lock.setType(AccessCredentialType.LOCK);
        lock.setReference("LOCK-018");
        lock.setLockCodeHeld(true);
        lock.setReservation(new Reservation());
        lock.setRental(rental);
        lock.setFacility(facility);
        lock.setStorageUnit(new StorageUnit());
        lock.setCustomer(new User());
        lock.setIssuedBy(operator);
    }

    @Test
    void verifiedReturnRevokesAccessAndReleasesCodeAtInspection() {
        activeLock();

        assertEquals(1, service.revokeForReturnInspection(staff, rental, "Nghiệm thu", LockInspectionResult.RETURNED_USABLE));

        assertEquals(AccessCredentialStatus.REVOKED, lock.getStatus());
        assertFalse(lock.isLockCodeHeld());
    }

    @ParameterizedTest
    @EnumSource(value = LockInspectionResult.class, names = {"MISSING_OR_INCOMPLETE", "DAMAGED", "UNVERIFIED"})
    @NullSource
    void incompleteInspectionRevokesAccessAndKeepsCodeBlocked(LockInspectionResult result) {
        activeLock();

        service.revokeForReturnInspection(staff, rental, "Nghiệm thu", result);

        assertEquals(AccessCredentialStatus.REVOKED, lock.getStatus());
        assertTrue(lock.isLockCodeHeld());
    }

    @Test
    void clearingUnitDoesNotReleaseLockButLaterVerifiedInspectionCan() {
        activeLock();
        service.revokeForRental(staff, rental, "Gian trống");
        assertTrue(lock.isLockCodeHeld());

        when(credentials.findByRental_IdAndStatusOrderById(rental.getId(), AccessCredentialStatus.ACTIVE))
            .thenReturn(List.of());
        when(credentials.findFirstByRental_IdAndTypeOrderByIssuedAtDescIdDesc(rental.getId(), AccessCredentialType.LOCK))
            .thenReturn(Optional.of(lock));

        assertEquals(0, service.revokeForReturnInspection(staff, rental, "Nghiệm thu", LockInspectionResult.RETURNED_USABLE));
        assertFalse(lock.isLockCodeHeld());
    }

    private void activeLock() {
        when(credentials.findByRental_IdAndStatusOrderById(rental.getId(), AccessCredentialStatus.ACTIVE))
            .thenReturn(List.of(lock));
    }
}
