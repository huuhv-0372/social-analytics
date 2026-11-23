package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.service.DashboardEventService;
import com.huuhv.socialanalytics.service.ImportHistoryService;
import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import com.huuhv.socialanalytics.websocket.DashboardEventPayload;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Event dashboard trên broker Artemis embedded thật: {@code DashboardEventService} → (sau commit) JMS topic
 * {@value TopicNames#DASHBOARD_EVENTS} → mọi subscriber. "Instance khác" được mô phỏng bằng 1 subscriber JMS riêng
 * trên cùng topic: nếu topic bị gửi nhầm thành queue, chỉ 1 trong 2 subscriber nhận được.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DashboardEventFanOutIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Pattern OCCURRED_AT = Pattern.compile("\"occurredAt\":\"([^\"]+)\"");

    @Autowired
    private DashboardEventService dashboardEventService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private JmsListenerEndpointRegistry listenerRegistry;

    @Autowired
    @Qualifier("brokerMessageConverter")
    private MessageConverter brokerMessageConverter;

    // Cùng bộ spy với ImportCompletedFlowIntegrationTest / ImportCompletedDlqIntegrationTest (dùng chung context)
    @MockitoSpyBean
    private ImportStatisticsService importStatisticsService;

    @MockitoSpyBean
    private ImportHistoryService importHistoryService;

    @MockitoSpyBean
    private DashboardEventBroadcaster dashboardEventBroadcaster;

    private Connection otherInstance;
    private MessageConsumer otherInstanceSubscriber;
    private Instant testStartedAt;

    @BeforeEach
    void subscribeAsAnotherInstance() throws JMSException {
        testStartedAt = Instant.now();
        // Subscription non-durable chỉ nhận message gửi sau khi đã đăng ký → chờ listener của app đăng ký xong
        DefaultMessageListenerContainer container = (DefaultMessageListenerContainer)
                listenerRegistry.getListenerContainer(DashboardEventListener.LISTENER_ID);
        await().atMost(WAIT).until(container::isRegisteredWithDestination);

        otherInstance = ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory).createConnection();
        Session session = otherInstance.createSession(false, Session.AUTO_ACKNOWLEDGE);
        otherInstanceSubscriber = session.createConsumer(session.createTopic(TopicNames.DASHBOARD_EVENTS));
        otherInstance.start();
    }

    @AfterEach
    void closeOtherInstance() throws JMSException {
        otherInstance.close();
    }

    @Test
    void eventOutsideTransactionReachesEveryInstance() throws JMSException {
        dashboardEventService.metricsUpdated(Set.of(42L, 43L));

        // Instance này: listener topic → WebSocket của các user đó
        verify(dashboardEventBroadcaster, timeout(WAIT.toMillis()))
                .sendToUsers(eq(DashboardEventType.METRICS_UPDATED), occurredInThisTest(), eq(List.of(42L, 43L)));
        // "Instance khác" cũng nhận được (user có thể nối vào instance đó), JSON có alias + id user, không có số liệu
        TextMessage received = receiveFromThisTest(WAIT);
        assertThat(received).isNotNull();
        assertThat(received.getStringProperty("_type")).isEqualTo(MessageTypes.DASHBOARD_EVENT);
        assertThat(received.getText()).contains("\"type\":\"METRICS_UPDATED\"", "\"userIds\":[42,43]")
                .doesNotContain("likes");
    }

    @Test
    void eventInsideCommittedTransactionIsSentAfterCommit() throws JMSException {
        transactionTemplate.executeWithoutResult(status -> dashboardEventService.importProcessed(42L));

        verify(dashboardEventBroadcaster, timeout(WAIT.toMillis()))
                .sendToUsers(eq(DashboardEventType.IMPORT_PROCESSED), occurredInThisTest(), eq(List.of(42L)));
        assertThat(receiveFromThisTest(WAIT)).isNotNull();
    }

    @Test
    void eventInsideRolledBackTransactionIsNeverSent() throws JMSException {
        transactionTemplate.executeWithoutResult(status -> {
            dashboardEventService.importProcessed(42L);
            status.setRollbackOnly();
        });

        verify(dashboardEventBroadcaster, after(1_500).never()).sendToUsers(any(), occurredInThisTest(), any());
        assertThat(receiveFromThisTest(Duration.ofMillis(200))).isNull();
    }

    @Test
    void stompPayloadIsSmallIsoJson() {
        Message<?> message = brokerMessageConverter.toMessage(
                new DashboardEventPayload(DashboardEventType.METRICS_UPDATED, Instant.parse("2026-11-12T03:00:00Z")),
                null);

        assertThat(message).isNotNull();
        assertThat(new String((byte[]) message.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"METRICS_UPDATED\",\"occurredAt\":\"2026-11-12T03:00:00Z\"}");
    }

    /** Message đầu tiên trên subscriber "instance khác" có {@code occurredAt} thuộc test này; {@code null} nếu hết giờ. */
    private TextMessage receiveFromThisTest(Duration wait) throws JMSException {
        long deadline = System.nanoTime() + wait.toNanos();
        long remainingMs;
        while ((remainingMs = (deadline - System.nanoTime()) / 1_000_000) > 0) {
            TextMessage message = (TextMessage) otherInstanceSubscriber.receive(remainingMs);
            if (message == null) {
                return null;
            }
            Matcher occurredAt = OCCURRED_AT.matcher(message.getText());
            if (occurredAt.find() && !Instant.parse(occurredAt.group(1)).isBefore(testStartedAt)) {
                return message;
            }
        }
        return null;
    }

    /** Bỏ qua event muộn của test khác dùng chung context (xem ImportCompletedFlowIntegrationTest). */
    private Instant occurredInThisTest() {
        return argThat(occurredAt -> occurredAt != null && !occurredAt.isBefore(testStartedAt));
    }
}
