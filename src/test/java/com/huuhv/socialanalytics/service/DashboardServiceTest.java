package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.dto.response.ImportStatisticsResponse;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private ImportStatisticsService importStatisticsService;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(currentUserService, socialAccountService, importStatisticsService,
                Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), SERVER_ZONE));
    }

    @Test
    void lastUpdatedAtOfCurrentUserIsUtcInstantWithServerZoneForDisplay() {
        givenCurrentUser(7L);
        when(socialAccountService.findLastMetricsUpdatedAt(7L))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 11, 7, 3, 0, 5)));

        DashboardResponse dashboard = service.getDashboard();

        assertThat(dashboard.lastUpdatedAt()).isEqualTo(Instant.parse("2026-11-07T03:00:05Z"));
        // Template hiển thị theo múi giờ server (qua ngày khi đổi từ UTC sang +07)
        assertThat(dashboard.zone()).isEqualTo(SERVER_ZONE);
        assertThat(dashboard.lastUpdatedAt().atZone(dashboard.zone()))
                .isEqualTo(ZonedDateTime.of(2026, 11, 7, 10, 0, 5, 0, SERVER_ZONE));
    }

    @Test
    void importStatisticsOfCurrentUserUseSameDtoAsChartApi() {
        givenCurrentUser(7L);
        when(importStatisticsService.findByUserId(7L)).thenReturn(Optional.of(UserImportStatistic.builder()
                .userId(7L).totalImports(3).totalRows(25).successRows(20).failedRows(5)
                .lastImportAt(LocalDateTime.of(2026, 11, 6, 18, 15)).build()));

        DashboardResponse dashboard = service.getDashboard();

        assertThat(dashboard.importStatistics()).isEqualTo(new ImportStatisticsResponse(3, 25, 20, 5,
                Instant.parse("2026-11-06T18:15:00Z")));
        verify(importStatisticsService).findByUserId(7L);
    }

    @Test
    void emptyValuesWhenNothingHasBeenCollectedForUser() {
        givenCurrentUser(7L);

        DashboardResponse dashboard = service.getDashboard();

        assertThat(dashboard.lastUpdatedAt()).isNull();
        assertThat(dashboard.importStatistics()).isNull();
    }

    @Test
    void currentUserIsResolvedOnceForWholePage() {
        givenCurrentUser(7L);

        service.getDashboard();

        verify(currentUserService).getCurrentUser();
    }

    @Test
    void failsClosedWhenCurrentUserIsUnknown() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(service::getDashboard).isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(socialAccountService, importStatisticsService);
    }

    private void givenCurrentUser(long id) {
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(id).build());
        // Mock trả Optional.empty() mặc định (RETURNS_DEFAULTS của Mockito) cho tài khoản/thống kê chưa stub
    }
}
