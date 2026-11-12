package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;

/**
 * Tổng metric của các bài thuộc 1 provider tại 1 mốc thời gian (projection {@code SELECT new}, không load Entity):
 * mỗi bài lấy snapshot mới nhất trước mốc đó rồi cộng lại.
 */
public record ProviderMetricTotals(Provider provider, Long likes, Long shares, Long comments, Long followers) {
}
