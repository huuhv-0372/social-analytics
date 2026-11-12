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

import static org.mockito.Mockito.verify;

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
    void metricsUpdatedPublishesEventWithCurrentInstant() {
        service.metricsUpdated();

        verify(eventPublisher).publishEvent(new DashboardChangedEvent(DashboardEventType.METRICS_UPDATED, NOW));
    }

    @Test
    void importProcessedPublishesEventWithCurrentInstant() {
        service.importProcessed();

        verify(eventPublisher).publishEvent(new DashboardChangedEvent(DashboardEventType.IMPORT_PROCESSED, NOW));
    }
}
