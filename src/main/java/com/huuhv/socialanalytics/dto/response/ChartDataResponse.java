package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Dữ liệu dashboard của user đang đăng nhập: biểu đồ metric theo provider/ngày + các số liệu dashboard cập nhật cùng
 * lúc (client gọi lại endpoint này khi nhận event WebSocket).
 *
 * @param zone             múi giờ server — ranh giới ngày của {@code labels}; client format {@code lastUpdatedAt}
 *                         theo múi giờ này để khớp trang render bằng Thymeleaf
 * @param labels           các ngày (theo {@code zone}), cũ → mới
 * @param series           1 phần tử cho mỗi {@link Provider} (kể cả provider chưa có dữ liệu)
 * @param lastUpdatedAt    lần job cập nhật metrics thành công gần nhất; {@code null} = chưa cập nhật
 * @param importStatistics thống kê import tổng hợp; {@code null} = chưa có lần import nào được tổng hợp
 */
public record ChartDataResponse(String zone, List<LocalDate> labels, List<ProviderSeries> series,
                                Instant lastUpdatedAt, ImportStatisticsResponse importStatistics) {

    /**
     * Giá trị metric của 1 provider theo từng ngày trong {@code labels} (cùng độ dài, cùng thứ tự). Mỗi bài lấy
     * snapshot mới nhất tính tới hết ngày đó (bài không được crawl trong ngày giữ giá trị trước đó); likes / shares /
     * comments = tổng các bài, followers = của snapshot mới nhất theo thời gian (số người theo dõi tác giả);
     * {@code null} = chưa có bài nào của provider có snapshot tới ngày đó.
     */
    public record ProviderSeries(Provider provider, List<Long> likes, List<Long> shares, List<Long> comments,
                                 List<Long> followers) {
    }
}
