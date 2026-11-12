package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;

/**
 * Metric tổng hợp của các bài thuộc 1 provider tại 1 mốc thời gian (projection {@code SELECT new}, không load Entity):
 * mỗi bài lấy snapshot mới nhất trước mốc đó.
 *
 * @param likes     tổng các bài (tương tự {@code shares}, {@code comments})
 * @param followers số người theo dõi tác giả — lớn nhất trong các bài, không cộng (cùng 1 tài khoản)
 */
public record ProviderMetricTotals(Provider provider, Long likes, Long shares, Long comments, Long followers) {
}
