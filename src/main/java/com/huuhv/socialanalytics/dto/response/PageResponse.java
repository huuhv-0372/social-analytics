package com.huuhv.socialanalytics.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Cấu trúc phân trang ổn định cho API (không serialize trực tiếp {@code PageImpl}).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
