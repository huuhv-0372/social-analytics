package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ProviderSeries;
import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.dto.response.ProviderDailyTotals;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dữ liệu biểu đồ dashboard của user đang đăng nhập: metric tổng hợp theo provider, theo ngày.
 * <p>
 * Snapshot lưu giá trị tuyệt đối (tích luỹ) nên không cộng mọi snapshot trong ngày. Giá trị ngày {@code d} lấy snapshot
 * mới nhất của từng bài tính tới hết ngày {@code d} theo múi giờ server ({@link Clock#getZone()}) — bài không được crawl
 * trong ngày giữ giá trị trước đó (carry-forward), đường biểu đồ không tụt giả. DB lưu giờ UTC nên ranh giới ngày được
 * tính trong Java (đúng cả múi giờ có DST), không dùng {@code DATE(collected_at)} trong SQL.
 * <p>
 * Mọi ngày tổng hợp trong <b>1</b> native query ({@link SocialMetricService#sumLatestByProviderPerDay}); Java chỉ xếp
 * kết quả đã tổng hợp vào đúng ngày / provider, không cộng.
 */
@Service
@RequiredArgsConstructor
public class ChartDataService {

    public static final int MAX_DAYS = 30;

    private final CurrentUserService currentUserService;
    private final SocialMetricService socialMetricService;
    private final DashboardService dashboardService;
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
        List<LocalDateTime> dayEndsUtc = new ArrayList<>(days);
        for (LocalDate day = today.minusDays(days - 1L); !day.isAfter(today); day = day.plusDays(1)) {
            labels.add(day);
            // Hết ngày = đầu ngày kế tiếp theo múi giờ server (atStartOfDay xử lý cả ngày chuyển DST)
            dayEndsUtc.add(UtcDateTimes.toUtc(day.plusDays(1).atStartOfDay(zone).toInstant()));
        }

        Map<Provider, SeriesBuilder> series = new EnumMap<>(Provider.class);
        for (Provider provider : Provider.values()) {
            series.put(provider, new SeriesBuilder(days));
        }
        for (ProviderDailyTotals totals : socialMetricService.sumLatestByProviderPerDay(userId, dayEndsUtc)) {
            int index = Math.toIntExact(totals.getDayIndex()) - 1;
            series.get(totals.getProvider()).set(index, totals);
        }

        List<ProviderSeries> providerSeries = series.entrySet().stream()
                .map(entry -> entry.getValue().build(entry.getKey()))
                .toList();
        // "Last updated time" + thống kê import: cùng cách tính với trang /dashboard
        DashboardResponse summary = dashboardService.loadSummary(userId);
        return new ChartDataResponse(clientZoneId(zone), List.copyOf(labels), providerSeries,
                summary.lastUpdatedAt(), summary.importStatistics());
    }

    /**
     * Id múi giờ trình duyệt đọc được ({@code Intl.DateTimeFormat}): tên IANA giữ nguyên; múi giờ cố định viết dạng
     * {@code GMT+07:00} (vd {@code -Duser.timezone=GMT+07:00}) → {@code +07:00}; UTC ({@code Z}) → {@code UTC}.
     */
    static String clientZoneId(ZoneId zone) {
        ZoneId normalized = zone.normalized();
        return ZoneOffset.UTC.equals(normalized) ? "UTC" : normalized.getId();
    }

    /** Giá trị từng ngày của 1 provider; ngày chưa có dữ liệu là {@code null} (không phải 0). */
    private static final class SeriesBuilder {

        private final List<Long> likes;
        private final List<Long> shares;
        private final List<Long> comments;
        private final List<Long> followers;

        SeriesBuilder(int days) {
            likes = new ArrayList<>(Collections.nCopies(days, null));
            shares = new ArrayList<>(Collections.nCopies(days, null));
            comments = new ArrayList<>(Collections.nCopies(days, null));
            followers = new ArrayList<>(Collections.nCopies(days, null));
        }

        void set(int index, ProviderDailyTotals totals) {
            likes.set(index, totals.getLikes());
            shares.set(index, totals.getShares());
            comments.set(index, totals.getComments());
            followers.set(index, totals.getFollowers());
        }

        ProviderSeries build(Provider provider) {
            // List.copyOf không nhận null → bọc chỉ đọc
            return new ProviderSeries(provider, Collections.unmodifiableList(likes),
                    Collections.unmodifiableList(shares), Collections.unmodifiableList(comments),
                    Collections.unmodifiableList(followers));
        }
    }
}
