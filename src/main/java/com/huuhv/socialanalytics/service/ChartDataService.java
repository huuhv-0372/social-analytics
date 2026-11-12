package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ImportStatistics;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ProviderSeries;
import com.huuhv.socialanalytics.dto.response.ProviderMetricTotals;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Dữ liệu biểu đồ dashboard của user đang đăng nhập: metric tổng hợp theo provider, theo ngày.
 * <p>
 * Snapshot lưu giá trị tuyệt đối (tích luỹ) nên không cộng mọi snapshot trong ngày. Giá trị ngày {@code d} = tổng
 * snapshot mới nhất của từng bài tính tới hết ngày {@code d} theo múi giờ server ({@link Clock#getZone()}) — bài không
 * được crawl trong ngày giữ giá trị trước đó (carry-forward), đường biểu đồ không tụt giả. DB lưu giờ UTC nên ranh giới
 * ngày được tính trong Java (đúng cả múi giờ có DST), không dùng {@code DATE(collected_at)} trong SQL.
 * <p>
 * Mỗi ngày 1 query tổng hợp {@code SELECT new} (≤ {@value #MAX_DAYS} query, mỗi query O(số bài) trên index) chạy trong
 * 1 transaction chỉ đọc → các ngày cùng 1 consistent read của InnoDB. Không load Entity rồi cộng trong Java.
 */
@Service
@RequiredArgsConstructor
public class ChartDataService {

    public static final int MAX_DAYS = 30;

    private final CurrentUserService currentUserService;
    private final SocialMetricService socialMetricService;
    private final SocialAccountService socialAccountService;
    private final ImportStatisticsService importStatisticsService;
    private final Clock clock;

    /**
     * @param days số ngày gần nhất (tính cả hôm nay), 1..{@value #MAX_DAYS}
     */
    @Transactional(readOnly = true)
    public ChartDataResponse getChartData(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("days must be between 1 and " + MAX_DAYS);
        }
        Long userId = currentUserService.getCurrentUser().getId();
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);

        List<LocalDate> labels = new ArrayList<>(days);
        Map<Provider, SeriesBuilder> series = new EnumMap<>(Provider.class);
        for (Provider provider : Provider.values()) {
            series.put(provider, new SeriesBuilder(days));
        }
        for (LocalDate day = today.minusDays(days - 1L); !day.isAfter(today); day = day.plusDays(1)) {
            labels.add(day);
            // Hết ngày = đầu ngày kế tiếp theo múi giờ server (atStartOfDay xử lý cả ngày chuyển DST)
            LocalDateTime endOfDayUtc = UtcDateTimes.toUtc(day.plusDays(1).atStartOfDay(zone).toInstant());
            Map<Provider, ProviderMetricTotals> totals = new EnumMap<>(Provider.class);
            socialMetricService.sumLatestByProvider(userId, endOfDayUtc)
                    .forEach(total -> totals.put(total.provider(), total));
            series.forEach((provider, builder) -> builder.add(totals.get(provider)));
        }

        List<ProviderSeries> providerSeries = series.entrySet().stream()
                .map(entry -> entry.getValue().build(entry.getKey()))
                .toList();
        ImportStatistics importStatistics = importStatisticsService.findByUserId(userId)
                .map(ImportStatistics::from)
                .orElse(null);
        return new ChartDataResponse(zone.getId(), List.copyOf(labels), providerSeries,
                UtcDateTimes.toInstant(socialAccountService.findLastMetricsUpdatedAt(userId).orElse(null)),
                importStatistics);
    }

    /** Gom giá trị từng ngày của 1 provider; ngày chưa có dữ liệu là {@code null} (không phải 0). */
    private static final class SeriesBuilder {

        private final List<Long> likes;
        private final List<Long> shares;
        private final List<Long> comments;
        private final List<Long> followers;

        SeriesBuilder(int days) {
            likes = new ArrayList<>(days);
            shares = new ArrayList<>(days);
            comments = new ArrayList<>(days);
            followers = new ArrayList<>(days);
        }

        void add(ProviderMetricTotals total) {
            likes.add(valueOf(total, ProviderMetricTotals::likes));
            shares.add(valueOf(total, ProviderMetricTotals::shares));
            comments.add(valueOf(total, ProviderMetricTotals::comments));
            followers.add(valueOf(total, ProviderMetricTotals::followers));
        }

        ProviderSeries build(Provider provider) {
            // List.copyOf không nhận null → bọc chỉ đọc
            return new ProviderSeries(provider, Collections.unmodifiableList(likes),
                    Collections.unmodifiableList(shares), Collections.unmodifiableList(comments),
                    Collections.unmodifiableList(followers));
        }

        private static Long valueOf(ProviderMetricTotals total, Function<ProviderMetricTotals, Long> metric) {
            return total == null ? null : metric.apply(total);
        }
    }
}
