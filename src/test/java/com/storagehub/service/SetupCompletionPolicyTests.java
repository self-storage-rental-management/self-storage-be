package com.storagehub.service;
import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SetupCompletionPolicyTests {
    @Test void capturesEachSizeWithoutGuessingUnknownSizes() {
        assertThat(SetupCompletionPolicy.days("S")).containsExactly(3,2);
        assertThat(SetupCompletionPolicy.days("M")).containsExactly(5,3);
        assertThat(SetupCompletionPolicy.days("L")).containsExactly(7,4);
        assertThat(SetupCompletionPolicy.days("XL")).containsExactly(10,5);
        assertThat(SetupCompletionPolicy.days("CUSTOM")).isNull();
    }
    @Test void additionalSetupKeepsFreeBoundaryAndSnapshotStable() {
        var r = new Rental(); var u = new StorageUnit(); var t = new UnitType();
        t.setCode("M"); u.setUnitType(t); r.setStorageUnit(u);
        Instant freeEnd = Instant.parse("2026-11-27T08:45:00Z"); r.setSetupEndsAt(freeEnd);
        SetupCompletionPolicy.initialize(r);
        assertThat(r.getSetupCompletionDeadlineAt()).isEqualTo(Instant.parse("2026-12-02T08:45:00Z"));
        assertThat(r.getSetupEndsAt()).isEqualTo(freeEnd);
        t.setCode("XL"); SetupCompletionPolicy.initialize(r);
        assertThat(r.getSetupExtensionDays()).isEqualTo(3);
    }
    @Test void rejectsExpiredThirdAndOversizedExtensions() {
        var r = new Rental(); Instant due=Instant.parse("2026-12-01T10:00:00Z");
        r.setSetupCompletionDeadlineAt(due); r.setSetupExtensionDays(2); r.setSetupExtensionCount(1);
        assertThat(SetupCompletionPolicy.canExtend(r, due.minusSeconds(1), due.plusSeconds(172800))).isTrue();
        assertThat(SetupCompletionPolicy.canExtend(r, due, due.plusSeconds(1))).isFalse();
        assertThat(SetupCompletionPolicy.canExtend(r, due.minusSeconds(1), due.plusSeconds(172801))).isFalse();
        r.setSetupExtensionCount(2);
        assertThat(SetupCompletionPolicy.canExtend(r, due.minusSeconds(1), due.plusSeconds(1))).isFalse();
    }
    @Test void readinessClosesExtensionEvenBeforeDeadline() {
        var r = new Rental(); var due=Instant.now().plusSeconds(3600);
        r.setSetupCompletionDeadlineAt(due); r.setSetupExtensionDays(2); r.setSetupExtensionCount(0); r.setMoveInCompletedAt(Instant.now());
        assertThat(SetupCompletionPolicy.canExtend(r, Instant.now(), due.plusSeconds(3600))).isFalse();
    }
}
