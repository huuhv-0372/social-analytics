package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DashboardEventListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-12T03:00:00Z");

    @Mock
    private DashboardEventBroadcaster broadcaster;

    @InjectMocks
    private DashboardEventListener listener;

    @Test
    void forwardsEventToLocalWebSocketClients() {
        listener.onDashboardEvent(new DashboardEventMessage(DashboardEventType.METRICS_UPDATED, OCCURRED_AT));

        verify(broadcaster).broadcast(DashboardEventType.METRICS_UPDATED, OCCURRED_AT);
    }

    static Stream<Arguments> incompleteMessages() {
        return Stream.of(arguments(null, OCCURRED_AT), arguments(DashboardEventType.METRICS_UPDATED, null),
                arguments(null, null));
    }

    @ParameterizedTest
    @MethodSource("incompleteMessages")
    void incompleteMessageIsDroppedWithoutBroadcast(DashboardEventType type, Instant occurredAt) {
        listener.onDashboardEvent(new DashboardEventMessage(type, occurredAt));

        verifyNoInteractions(broadcaster);
    }
}
