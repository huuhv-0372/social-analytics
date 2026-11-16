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
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
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
    void forwardsEventToTargetUsersConnectedHere() {
        listener.onDashboardEvent(new DashboardEventMessage(DashboardEventType.METRICS_UPDATED, OCCURRED_AT,
                List.of(7L, 9L)));

        verify(broadcaster).sendToUsers(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, List.of(7L, 9L));
    }

    static Stream<Arguments> invalidMessages() {
        List<Long> users = List.of(7L);
        List<Long> tooMany = LongStream.rangeClosed(1, DashboardEventMessage.MAX_USER_IDS + 1).boxed().toList();
        return Stream.of(
                arguments(null, OCCURRED_AT, users),
                arguments(DashboardEventType.METRICS_UPDATED, null, users),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, null),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, List.of()),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, List.of(0L)),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, List.of(-3L)),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, Arrays.asList(7L, null)),
                arguments(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, tooMany));
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void invalidMessageIsDroppedWithoutSendingAnything(DashboardEventType type, Instant occurredAt,
                                                       List<Long> userIds) {
        listener.onDashboardEvent(new DashboardEventMessage(type, occurredAt, userIds));

        verifyNoInteractions(broadcaster);
    }
}
