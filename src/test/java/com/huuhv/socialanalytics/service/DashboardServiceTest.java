package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private SocialAccountService socialAccountService;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(currentUserService, socialAccountService,
                Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), SERVER_ZONE));
    }

    @Test
    void convertsUtcLastUpdatedAtOfCurrentUserToServerTimeZone() {
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(7L).build());
        when(socialAccountService.findLastMetricsUpdatedAt(7L))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 11, 7, 3, 0, 5)));

        assertThat(service.findLastUpdatedAt())
                .contains(ZonedDateTime.of(2026, 11, 7, 10, 0, 5, 0, SERVER_ZONE));
    }

    @Test
    void conversionCrossesDateBoundary() {
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(7L).build());
        when(socialAccountService.findLastMetricsUpdatedAt(7L))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 11, 6, 20, 30)));

        assertThat(service.findLastUpdatedAt())
                .contains(ZonedDateTime.of(2026, 11, 7, 3, 30, 0, 0, SERVER_ZONE));
    }

    @Test
    void emptyWhenNoAccountOfUserHasBeenUpdated() {
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(7L).build());
        when(socialAccountService.findLastMetricsUpdatedAt(7L)).thenReturn(Optional.empty());

        assertThat(service.findLastUpdatedAt()).isEmpty();
    }

    @Test
    void failsClosedWhenCurrentUserIsUnknown() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(service::findLastUpdatedAt).isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(socialAccountService);
    }
}
