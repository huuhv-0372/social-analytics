package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ImportStatistics;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ProviderSeries;
import com.huuhv.socialanalytics.dto.response.ProviderMetricTotals;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartDataServiceTest {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // 2026-11-07 12:00 giờ server
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), SERVER_ZONE);
    private static final long USER_ID = 7L;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private SocialMetricService socialMetricService;

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private ImportStatisticsService importStatisticsService;

    @Test
    void aggregatesEachDayPerProviderWithNullBeforeFirstSnapshot() {
        givenCurrentUser();
        // Hết ngày theo giờ server (+07) = 17:00 UTC cùng ngày
        when(socialMetricService.sumLatestByProvider(USER_ID, LocalDateTime.of(2026, 11, 5, 17, 0)))
                .thenReturn(List.of());
        when(socialMetricService.sumLatestByProvider(USER_ID, LocalDateTime.of(2026, 11, 6, 17, 0)))
                .thenReturn(List.of(new ProviderMetricTotals(Provider.FACEBOOK, 10L, 1L, 2L, 100L)));
        when(socialMetricService.sumLatestByProvider(USER_ID, LocalDateTime.of(2026, 11, 7, 17, 0)))
                .thenReturn(List.of(new ProviderMetricTotals(Provider.TWITTER, 5L, 6L, 7L, 8L),
                        new ProviderMetricTotals(Provider.FACEBOOK, 20L, 3L, 4L, 110L)));

        ChartDataResponse chart = service(CLOCK).getChartData(3);

        assertThat(chart.zone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(chart.labels()).containsExactly(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7));
        // Mọi provider đều có series, theo thứ tự enum, cùng độ dài với labels
        assertThat(chart.series()).containsExactly(
                new ProviderSeries(Provider.FACEBOOK, values(null, 10L, 20L), values(null, 1L, 3L),
                        values(null, 2L, 4L), values(null, 100L, 110L)),
                new ProviderSeries(Provider.TWITTER, values(null, null, 5L), values(null, null, 6L),
                        values(null, null, 7L), values(null, null, 8L)));
    }

    @Test
    void dayBoundariesFollowServerZoneAcrossDaylightSavingChange() {
        givenCurrentUser();
        when(socialMetricService.sumLatestByProvider(eq(USER_ID), any())).thenReturn(List.of());
        ZoneId newYork = ZoneId.of("America/New_York");
        // DST bắt đầu 2026-03-08 lúc 02:00 (−05:00 → −04:00)
        Clock clock = Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), newYork);

        ChartDataResponse chart = service(clock).getChartData(3);

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(socialMetricService, times(3)).sumLatestByProvider(eq(USER_ID), before.capture());
        assertThat(chart.labels()).containsExactly(
                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9));
        assertThat(before.getAllValues()).containsExactly(
                LocalDateTime.of(2026, 3, 8, 5, 0),   // 03-08 00:00 EST
                LocalDateTime.of(2026, 3, 9, 4, 0),   // 03-09 00:00 EDT (ngày 03-08 chỉ có 23 giờ)
                LocalDateTime.of(2026, 3, 10, 4, 0));
    }

    @Test
    void includesLastUpdatedAtAndImportStatisticsAsInstants() {
        givenCurrentUser();
        when(socialMetricService.sumLatestByProvider(eq(USER_ID), any())).thenReturn(List.of());
        when(socialAccountService.findLastMetricsUpdatedAt(USER_ID))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 11, 7, 3, 0, 5)));
        when(importStatisticsService.findByUserId(USER_ID)).thenReturn(Optional.of(UserImportStatistic.builder()
                .userId(USER_ID).totalImports(3).totalRows(25).successRows(20).failedRows(5)
                .lastImportAt(LocalDateTime.of(2026, 11, 6, 18, 15)).build()));

        ChartDataResponse chart = service(CLOCK).getChartData(1);

        assertThat(chart.labels()).containsExactly(LocalDate.of(2026, 11, 7));
        assertThat(chart.lastUpdatedAt()).isEqualTo(Instant.parse("2026-11-07T03:00:05Z"));
        assertThat(chart.importStatistics())
                .isEqualTo(new ImportStatistics(3, 25, 20, 5, Instant.parse("2026-11-06T18:15:00Z")));
    }

    @Test
    void emptyValuesWhenUserHasNoData() {
        givenCurrentUser();
        when(socialMetricService.sumLatestByProvider(eq(USER_ID), any())).thenReturn(List.of());

        ChartDataResponse chart = service(CLOCK).getChartData(2);

        assertThat(chart.series()).extracting(ProviderSeries::likes).containsOnly(values(null, null));
        assertThat(chart.lastUpdatedAt()).isNull();
        assertThat(chart.importStatistics()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, ChartDataService.MAX_DAYS + 1})
    void rejectsDaysOutOfRange(int days) {
        assertThatThrownBy(() -> service(CLOCK).getChartData(days)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(currentUserService, socialMetricService);
    }

    @Test
    void maxDaysRunsOneQueryPerDay() {
        givenCurrentUser();
        when(socialMetricService.sumLatestByProvider(eq(USER_ID), any())).thenReturn(List.of());

        ChartDataResponse chart = service(CLOCK).getChartData(ChartDataService.MAX_DAYS);

        assertThat(chart.labels()).hasSize(ChartDataService.MAX_DAYS)
                .startsWith(LocalDate.of(2026, 10, 9)).endsWith(LocalDate.of(2026, 11, 7));
        verify(socialMetricService, times(ChartDataService.MAX_DAYS)).sumLatestByProvider(eq(USER_ID), any());
    }

    @Test
    void failsClosedWhenCurrentUserIsUnknown() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(() -> service(CLOCK).getChartData(7)).isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(socialMetricService, socialAccountService, importStatisticsService);
    }

    private ChartDataService service(Clock clock) {
        return new ChartDataService(currentUserService, socialMetricService, socialAccountService,
                importStatisticsService, clock);
    }

    private void givenCurrentUser() {
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(USER_ID).build());
    }

    /** {@code List.of} không nhận {@code null}. */
    private static List<Long> values(Long... values) {
        return Arrays.asList(values);
    }
}
