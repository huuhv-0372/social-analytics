package com.huuhv.socialanalytics.util;

import com.huuhv.socialanalytics.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.stream.Collectors;

public final class PageableUtils {

    private PageableUtils() {
    }

    /** Khoá chính — mọi entity phân trang đều có, dùng làm tiêu chí phụ để thứ tự luôn xác định. */
    private static final String TIE_BREAKER = "id";

    /**
     * Chỉ cho phép sort theo field trong whitelist (OWASP A05); không truyền sort thì dùng {@code defaultSort}.
     * Luôn nối thêm {@code id DESC} làm tiêu chí phụ: phân trang offset trên cột có nhiều giá trị trùng
     * (vd {@code likes}) sẽ không bị lặp/bỏ sót dòng giữa các trang.
     */
    public static Pageable withAllowedSort(Pageable pageable, Set<String> allowedProperties, Sort defaultSort) {
        Sort sort = pageable.getSort().isUnsorted() ? defaultSort : pageable.getSort();
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedProperties.contains(order.getProperty())) {
                // Sắp xếp để thông điệp ổn định (thứ tự duyệt Set.of thay đổi giữa các lần chạy JVM)
                throw new BadRequestException("Sort property is not allowed. Allowed: "
                        + allowedProperties.stream().sorted().collect(Collectors.joining(", ")));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), withTieBreaker(sort));
    }

    private static Sort withTieBreaker(Sort sort) {
        return sort.getOrderFor(TIE_BREAKER) != null ? sort : sort.and(Sort.by(Sort.Direction.DESC, TIE_BREAKER));
    }
}
