package com.storagehub.api.payment;

import com.storagehub.common.api.ApiExceptions;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

public record ManagerPaymentHistoryQuery(int page, int size, UUID facilityId, String search) {
    private static final Set<String> ALLOWED = Set.of("page", "size", "facilityId", "search");

    public static ManagerPaymentHistoryQuery parse(MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry ->
            !ALLOWED.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            throw invalid();
        }
        try {
            int page = Integer.parseInt(query.getFirst("page") == null ? "0" : query.getFirst("page"));
            int size = Integer.parseInt(query.getFirst("size") == null ? "20" : query.getFirst("size"));
            String rawFacilityId = query.getFirst("facilityId");
            String search = query.getFirst("search") == null ? "" : query.getFirst("search").trim();
            if (page < 0 || size < 1 || size > 100 || (long) page * size > 10_000
                || rawFacilityId == null || search.length() > 200) {
                throw invalid();
            }
            return new ManagerPaymentHistoryQuery(page, size, UUID.fromString(rawFacilityId), search);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static RuntimeException invalid() {
        return ApiExceptions.validation("Unsupported payment history query", null);
    }
}
