package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.service.DashboardChangedEvent;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardEventProducerTest {

    private static final Topic TOPIC = ActiveMQJMSClient.createTopic(TopicNames.DASHBOARD_EVENTS);
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-12T03:00:00Z");

    @Mock
    private JmsTemplate jmsTemplate;

    private DashboardEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new DashboardEventProducer(jmsTemplate, TOPIC);
    }

    @Test
    void sendsEventWithTargetUsersToDashboardTopicNotToAQueueNamedLikeIt() {
        producer.onDashboardChanged(new DashboardChangedEvent(DashboardEventType.IMPORT_PROCESSED, OCCURRED_AT,
                Set.of(7L)));

        verify(jmsTemplate).convertAndSend(TOPIC,
                new DashboardEventMessage(DashboardEventType.IMPORT_PROCESSED, OCCURRED_AT, List.of(7L)));
    }

    @Test
    void manyUsersAreSplitIntoBoundedMessages() {
        int users = DashboardEventMessage.MAX_USER_IDS * 2 + 1;
        Set<Long> userIds = LongStream.rangeClosed(1, users).boxed().collect(Collectors.toSet());

        producer.onDashboardChanged(new DashboardChangedEvent(DashboardEventType.METRICS_UPDATED, OCCURRED_AT, userIds));

        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(jmsTemplate, times(3)).convertAndSend(eq(TOPIC), messages.capture());
        assertThat(messages.getAllValues()).map(DashboardEventMessage.class::cast)
                .allSatisfy(message -> {
                    assertThat(message.type()).isEqualTo(DashboardEventType.METRICS_UPDATED);
                    assertThat(message.userIds()).hasSizeLessThanOrEqualTo(DashboardEventMessage.MAX_USER_IDS);
                })
                .flatMap(DashboardEventMessage::userIds)
                .containsExactlyInAnyOrderElementsOf(userIds);
    }

    @Test
    void brokerFailureIsLoggedNotThrownBecauseDataIsAlreadyCommitted() {
        doThrow(new UncategorizedJmsException("broker down")).when(jmsTemplate).convertAndSend(eq(TOPIC), any(Object.class));

        assertThatCode(() -> producer.onDashboardChanged(
                new DashboardChangedEvent(DashboardEventType.IMPORT_PROCESSED, OCCURRED_AT, Set.of(7L))))
                .doesNotThrowAnyException();
    }
}
