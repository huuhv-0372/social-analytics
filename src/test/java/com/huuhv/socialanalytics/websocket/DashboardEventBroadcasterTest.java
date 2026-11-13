package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class DashboardEventBroadcasterTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-12T03:00:00Z");

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private DashboardEventBroadcaster broadcaster;

    @Test
    void sendsSmallPayloadToEachTargetUserDestination() {
        // User không kết nối vào instance này bị UserDestinationMessageHandler bỏ qua (instance khác tự gửi)
        broadcaster.sendToUsers(DashboardEventType.IMPORT_PROCESSED, OCCURRED_AT, List.of(7L, 9L));

        DashboardEventPayload payload = new DashboardEventPayload(DashboardEventType.IMPORT_PROCESSED, OCCURRED_AT);
        verify(messagingTemplate).convertAndSendToUser("7", StompDestinations.METRICS_QUEUE, payload);
        verify(messagingTemplate).convertAndSendToUser("9", StompDestinations.METRICS_QUEUE, payload);
        verifyNoMoreInteractions(messagingTemplate);
    }
}
