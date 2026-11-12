package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardEventBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private DashboardEventBroadcaster broadcaster;

    @Test
    void sendsSmallPayloadToMetricsTopic() {
        Instant occurredAt = Instant.parse("2026-11-12T03:00:00Z");

        broadcaster.broadcast(DashboardEventType.IMPORT_PROCESSED, occurredAt);

        verify(messagingTemplate).convertAndSend(StompDestinations.METRICS_TOPIC,
                new DashboardEventPayload(DashboardEventType.IMPORT_PROCESSED, occurredAt));
    }
}
