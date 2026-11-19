package com.storagehub.service.support.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.repo.FacilityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
class SupportPublicFacilityJpaTests {
    @Autowired FacilityRepository facilities;

    @Test
    void readsCurrentPublicContactAndExcludesInactiveFacilities() {
        var facility = new Facility();
        facility.setCode("AI-JPA-01");
        facility.setName("Cơ sở Phú Mỹ");
        facility.setAddress("Địa chỉ kiểm thử");
        facility.setCity("Bà Rịa");
        facility.setPhone("1900 0142");
        facility.setAccessHours("08:00 - 17:00");
        facilities.saveAndFlush(facility);

        var support = new SupportPublicFacility(facilities);
        var intent = SupportPublicFacility.intent("Giờ mở cửa và hotline cơ sở AI-JPA-01?").orElseThrow();
        assertThat(support.answer(intent)).contains("08:00 - 17:00", "1900 0142");
        facility.setAccessHours("09:00 - 18:00");
        facilities.saveAndFlush(facility);
        assertThat(support.answer(intent)).contains("09:00 - 18:00");
        facility.setStatus(FacilityStatus.inactive);
        facilities.saveAndFlush(facility);
        assertThat(support.answer(intent)).contains("chưa tìm thấy").doesNotContain("1900 0142");
    }
}
