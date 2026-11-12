package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.service.DashboardChangedEvent;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardEventProducerTest {

    private static final Topic TOPIC = ActiveMQJMSClient.createTopic(TopicNames.DASHBOARD_EVENTS);
    private static final DashboardChangedEvent EVENT =
            new DashboardChangedEvent(DashboardEventType.IMPORT_PROCESSED, Instant.parse("2026-11-12T03:00:00Z"));

    @Mock
    private JmsTemplate jmsTemplate;

    private DashboardEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new DashboardEventProducer(jmsTemplate, TOPIC);
    }

    @Test
    void sendsEventToDashboardTopicNotToAQueueNamedLikeIt() {
        producer.onDashboardChanged(EVENT);

        verify(jmsTemplate).convertAndSend(TOPIC,
                new DashboardEventMessage(DashboardEventType.IMPORT_PROCESSED, Instant.parse("2026-11-12T03:00:00Z")));
    }

    @Test
    void brokerFailureIsLoggedNotThrownBecauseDataIsAlreadyCommitted() {
        doThrow(new UncategorizedJmsException("broker down")).when(jmsTemplate).convertAndSend(eq(TOPIC), any(Object.class));

        assertThatCode(() -> producer.onDashboardChanged(EVENT)).doesNotThrowAnyException();
    }
}
