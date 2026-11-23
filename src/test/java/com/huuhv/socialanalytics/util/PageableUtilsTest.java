package com.huuhv.socialanalytics.util;

import com.huuhv.socialanalytics.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilsTest {

    private static final Set<String> ALLOWED = Set.of("createdAt", "provider");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void appliesDefaultSortWhenUnsorted() {
        Pageable result = PageableUtils.withAllowedSort(PageRequest.of(2, 10), ALLOWED, DEFAULT_SORT);

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getSort()).isEqualTo(DEFAULT_SORT.and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Test
    void keepsAllowedSortAndAppendsIdTieBreaker() {
        Pageable pageable = PageRequest.of(3, 10, Sort.by("provider").ascending());

        Pageable result = PageableUtils.withAllowedSort(pageable, ALLOWED, DEFAULT_SORT);

        assertThat(result.getPageNumber()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getSort()).containsExactly(Sort.Order.asc("provider"), Sort.Order.desc("id"));
    }

    @Test
    void doesNotDuplicateIdWhenAlreadySorted() {
        Set<String> allowedWithId = Set.of("provider", "id");
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Order.asc("id"), Sort.Order.asc("provider")));

        assertThat(PageableUtils.withAllowedSort(pageable, allowedWithId, DEFAULT_SORT).getSort())
                .containsExactly(Sort.Order.asc("id"), Sort.Order.asc("provider"));
    }

    @Test
    void rejectsSortOutsideWhitelist() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("createdAt", "user.email"));

        assertThatThrownBy(() -> PageableUtils.withAllowedSort(pageable, ALLOWED, DEFAULT_SORT))
                .isInstanceOf(BadRequestException.class)
                // Danh sách field được sắp xếp → thông điệp ổn định giữa các lần chạy
                .hasMessage("Sort property is not allowed. Allowed: createdAt, provider");
    }
}
