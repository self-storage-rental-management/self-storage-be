package com.storagehub.api.rental;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.common.api.ApiException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.LinkedMultiValueMap;

class RentalQueryTests {
    @Test void defaultsAndStableSort() {
        var q = RentalQuery.parse(new LinkedMultiValueMap<>(), false);
        assertThat(q.page()).isZero(); assertThat(q.size()).isEqualTo(20);
        assertThat(q.sort().toString()).isEqualTo("createdAt: DESC,id: ASC");
    }
    static Stream<String[]> invalidQueries() { return Stream.of(
        new String[]{"page","-1"}, new String[]{"size","101"}, new String[]{"size","0"},
        new String[]{"status","ACTIVE"}, new String[]{"sort","customer,asc"},
        new String[]{"sort","id,asc,desc"}, new String[]{"needsAttention","false"},
        new String[]{"endFrom","not-date"}, new String[]{"search","x".repeat(201)},
        new String[]{"customerId","anything"}, new String[]{"facilityId","not-uuid"}); }
    @ParameterizedTest @MethodSource("invalidQueries") void rejectsBadQueries(String key, String value) {
        var p = new LinkedMultiValueMap<String,String>(); p.add(key,value);
        assertThatThrownBy(() -> RentalQuery.parse(p,true)).isInstanceOf(ApiException.class);
    }
    @Test void rejectsRepeatedAndReversedAndCustomerFacility() {
        var p = new LinkedMultiValueMap<String,String>(); p.add("page","0"); p.add("page","1");
        assertThatThrownBy(() -> RentalQuery.parse(p,false)).isInstanceOf(ApiException.class);
        p.clear(); p.add("endFrom","2026-10-05"); p.add("endTo","2026-10-04");
        assertThatThrownBy(() -> RentalQuery.parse(p,false)).isInstanceOf(ApiException.class);
        p.clear(); p.add("facilityId",java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> RentalQuery.parse(p,false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RentalQuery.validateDetail(p)).isInstanceOf(ApiException.class);
    }
}
