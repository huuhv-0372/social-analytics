package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;

/**
 * Metric tổng hợp của 1 provider tại 1 mốc cuối ngày (projection của native query
 * {@code SocialMetricRepository.sumLatestByProviderPerDay}, không load Entity). Mỗi bài lấy snapshot mới nhất trước mốc.
 */
public interface ProviderDailyTotals {

    /** Vị trí mốc trong danh sách truyền vào, bắt đầu từ 1. */
    Long getDayIndex();

    Provider getProvider();

    /** Tổng các bài (tương tự {@link #getShares()}, {@link #getComments()}). */
    Long getLikes();

    Long getShares();

    Long getComments();

    /**
     * Số người theo dõi tác giả lấy từ snapshot <b>mới nhất theo thời gian</b> trong các bài của provider — không cộng
     * (lặp trên mọi bài của cùng tài khoản), không lấy MAX (giá trị cũ của bài không còn được crawl sẽ đè số mới).
     */
    Long getFollowers();
}
