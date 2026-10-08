package com.storagehub.api.rental;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.RentalStatus;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record RentalQuery(int page, int size, RentalStatus status, String search,
    LocalDate endFrom, LocalDate endTo, UUID facilityId, Sort sort) {
    public static RentalQuery parse(MultiValueMap<String, String> params, boolean manager) {
        Set<String> keys = manager
            ? Set.of("page", "size", "status", "search", "endFrom", "endTo", "sort", "facilityId")
            : Set.of("page", "size", "status", "search", "endFrom", "endTo", "sort");
        if (params.entrySet().stream().anyMatch(e -> !keys.contains(e.getKey()) || e.getValue().size() != 1))
            throw invalid();
        try {
            int page = Integer.parseInt(value(params, "page", "0"));
            int size = Integer.parseInt(value(params, "size", "20"));
            if (page < 0 || size < 1 || size > 100) throw invalid();
            String search = value(params, "search", "").trim();
            if (search.length() > 200) throw invalid();
            LocalDate from = params.containsKey("endFrom") ? LocalDate.parse(params.getFirst("endFrom")) : null;
            LocalDate to = params.containsKey("endTo") ? LocalDate.parse(params.getFirst("endTo")) : null;
            if (from != null && to != null && from.isAfter(to)) throw invalid();
            String[] order = value(params, "sort", "createdAt,desc").split(",", -1);
            if (order.length != 2 || !Set.of("createdAt", "contractEndDate", "startDate", "monthlyPrice", "id").contains(order[0])
                || !Set.of("asc", "desc").contains(order[1])) throw invalid();
            Sort sort = Sort.by(Sort.Direction.fromString(order[1]), order[0]);
            if (!order[0].equals("id")) sort = sort.and(Sort.by("id"));
            return new RentalQuery(page, size,
                params.containsKey("status") ? RentalStatus.valueOf(params.getFirst("status")) : null,
                search, from, to, params.containsKey("facilityId") ? UUID.fromString(params.getFirst("facilityId")) : null, sort);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw invalid();
        }
    }
    public PageRequest pageable() { return PageRequest.of(page, size, sort); }
    public static void validateDetail(MultiValueMap<String, String> params) {
        if (!params.isEmpty()) throw invalid();
    }
    private static String value(MultiValueMap<String, String> params, String key, String fallback) {
        return params.containsKey(key) ? params.getFirst(key) : fallback;
    }
    private static RuntimeException invalid() {
        return ApiExceptions.validation("Invalid rental query; unsupported or repeated parameters are not accepted", null);
    }
}
