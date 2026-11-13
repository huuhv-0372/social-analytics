package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DashboardEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DashboardEventService service;

    @BeforeEach
    void setUp() {
        service = new DashboardEventService(eventPublisher, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @Test
    void metricsUpdatedTargetsOwnersOfUpdatedAccountsOnce() {
        // Trùng id (user có cả Facebook lẫn X) → 1 user
        service.metricsUpdated(List.of(7L, 9L, 7L));

        verify(eventPublisher).publishEvent(
                new DashboardChangedEvent(DashboardEventType.METRICS_UPDATED, NOW, Set.of(7L, 9L)));
    }

    @Test
    void metricsUpdatedWithoutUsersPublishesNothing() {
        service.metricsUpdated(Set.of());

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void importProcessedTargetsOnlyTheOwner() {
        service.importProcessed(7L);

        verify(eventPublisher).publishEvent(
                new DashboardChangedEvent(DashboardEventType.IMPORT_PROCESSED, NOW, Set.of(7L)));
    }
}
