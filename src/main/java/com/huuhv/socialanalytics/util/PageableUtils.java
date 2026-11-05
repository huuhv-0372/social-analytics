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

    /**
     * Chỉ cho phép sort theo field trong whitelist (OWASP A05); không truyền sort thì dùng {@code defaultSort}.
     */
    public static Pageable withAllowedSort(Pageable pageable, Set<String> allowedProperties, Sort defaultSort) {
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedProperties.contains(order.getProperty())) {
                // Sắp xếp để thông điệp ổn định (thứ tự duyệt Set.of thay đổi giữa các lần chạy JVM)
                throw new BadRequestException("Sort property is not allowed. Allowed: "
                        + allowedProperties.stream().sorted().collect(Collectors.joining(", ")));
            }
        }
        return pageable;
    }
}
