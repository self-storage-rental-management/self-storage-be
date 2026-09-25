package com.storagehub.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
    List<T> data,
    Pagination pagination,
    String correlationId
) {
    public static <T> PageResponse<T> from(Page<T> page, String correlationId) {
        return new PageResponse<>(
            page.getContent(),
            new Pagination(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getSort().toString()
            ),
            correlationId
        );
    }

    public record Pagination(
        int page,
        int pageSize,
        long totalItems,
        int totalPages,
        String sort
    ) {
    }
}
