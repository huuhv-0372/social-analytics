package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.AliasOnlyJsonMessageConverter;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.UserImportStatisticRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.service.ImportHistoryService;
import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.service.PostUpsertService;
import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Retry + DLQ trên broker Artemis embedded (address settings từ {@code artemis/broker.properties} +
 * {@code artemis/broker-test.properties}: 6 lần giao, cách nhau 100ms) + MySQL Testcontainers. Cùng cấu hình context
 * (kể cả bộ {@code @MockitoSpyBean}) với {@link ImportCompletedFlowIntegrationTest} để dùng chung context đã cache.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ImportCompletedDlqIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long WAIT_MS = WAIT.toMillis();
    /** {@code maxDeliveryAttempts} của {@code import-completed} trong {@code artemis/broker.properties}. */
    private static final int MAX_DELIVERY_ATTEMPTS = 6;
    /** Số lần giao của address setting {@code #} mặc định (Artemis: 10) — DLQ.import-completed không được dùng nó. */
    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 10;

    @Autowired
    private PostUpsertService postUpsertService;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private ImportHistoryRepository importHistoryRepository;

    @Autowired
    private UserImportStatisticRepository userImportStatisticRepository;

    @MockitoSpyBean
    private ImportStatisticsService importStatisticsService;

    @MockitoSpyBean
    private ImportHistoryService importHistoryService;

    // Không dùng trong class này — giữ cùng bộ spy để dùng chung context đã cache
    @MockitoSpyBean
    private DashboardEventBroadcaster dashboardEventBroadcaster;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().displayName("Importer").build());
    }

    @AfterEach
    void cleanUp() {
        postRepository.deleteAll();
        importHistoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void failingMessageIsRetriedThenMovedToDlqAndImportMarkedDeadLettered(CapturedOutput output) {
        // Lỗi tạm thời kéo dài (DB không ghi được) ở mọi lần giao
        doThrow(new QueryTimeoutException("lock wait timeout"))
                .when(importStatisticsService).applyImport(anyLong(), any());

        long importId = postUpsertService.saveImport(owner, List.of(post("fb-1")), 1).importId();

        verify(importStatisticsService, timeout(WAIT_MS).times(MAX_DELIVERY_ATTEMPTS))
                .applyImport(importId, owner.getId());
        awaitStatus(importId, ImportStatus.DEAD_LETTERED);
        // Hết số lần giao → không thử thêm
        verify(importStatisticsService, after(1_000).times(MAX_DELIVERY_ATTEMPTS))
                .applyImport(importId, owner.getId());
        assertThat(userImportStatisticRepository.findById(owner.getId())).isEmpty();
        // Cảnh báo vận hành (OWASP A09) kèm importId
        assertThat(output).contains("moved to " + QueueNames.IMPORT_COMPLETED_DLQ)
                .contains("importId=" + importId);
    }

    @Test
    void deadLetteredImportIsCountedOnceWhenMessageIsResentAfterFix() {
        doThrow(new QueryTimeoutException("lock wait timeout"))
                .when(importStatisticsService).applyImport(anyLong(), any());
        long importId = postUpsertService.saveImport(owner, List.of(post("fb-1"), post("fb-2")), 2).importId();
        awaitStatus(importId, ImportStatus.DEAD_LETTERED);

        // Khắc phục lỗi rồi gửi lại message (dựng từ import_histories)
        doCallRealMethod().when(importStatisticsService).applyImport(anyLong(), any());
        ImportCompletedMessage resent = new ImportCompletedMessage(importId, owner.getId(), 2, 2, 0, Instant.now());
        jmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED, resent);
        awaitStatus(importId, ImportStatus.PROCESSED);
        // Gửi trùng lần nữa → không cộng 2 lần
        jmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED, resent);

        verify(importStatisticsService, timeout(WAIT_MS).times(MAX_DELIVERY_ATTEMPTS + 2))
                .applyImport(importId, owner.getId());
        assertThat(userImportStatisticRepository.findById(owner.getId())).get()
                .extracting(UserImportStatistic::getTotalImports, UserImportStatistic::getSuccessRows)
                .containsExactly(1L, 2L);
    }

    @Test
    void deadLetterIsRedeliveredUntilDatabaseRecoversInsteadOfFallingIntoGenericDlq() {
        doThrow(new QueryTimeoutException("lock wait timeout"))
                .when(importStatisticsService).applyImport(anyLong(), any());
        // DB vẫn lỗi khi DLQ listener đánh dấu DEAD_LETTERED — lâu hơn số lần giao của setting "#" mặc định
        int markFailures = DEFAULT_MAX_DELIVERY_ATTEMPTS + 2;
        AtomicInteger markCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (markCalls.incrementAndGet() <= markFailures) {
                throw new QueryTimeoutException("db down");
            }
            return invocation.callRealMethod();
        }).when(importHistoryService).markDeadLettered(anyLong());

        long importId = postUpsertService.saveImport(owner, List.of(post("fb-1")), 1).importId();

        // Không rơi sang queue "DLQ" chung (không có listener): DB hoạt động lại thì vẫn được đánh dấu
        awaitStatus(importId, ImportStatus.DEAD_LETTERED);
        verify(importHistoryService, times(markFailures + 1)).markDeadLettered(importId);
    }

    @Test
    void unreadableMessageEndsInDlqWithoutReachingService(CapturedOutput output) {
        // Message giả mạo: _type không có trong MessageTypes → mọi lần giao đều lỗi chuyển đổi
        jmsTemplate.send(QueueNames.IMPORT_COMPLETED, session -> {
            TextMessage message = session.createTextMessage("{\"importId\":1}");
            message.setStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY, "java.util.HashMap");
            return message;
        });

        await().atMost(WAIT).untilAsserted(() -> assertThat(output)
                .contains("moved to " + QueueNames.IMPORT_COMPLETED_DLQ)
                .contains("Dead-lettered message is unreadable"));
        verify(importStatisticsService, never()).applyImport(anyLong(), any());
    }

    private void awaitStatus(long importId, ImportStatus expected) {
        await().atMost(WAIT).until(() -> importHistoryRepository.findById(importId)
                .map(ImportHistory::getStatus).orElseThrow() == expected);
    }

    private static PostRequest post(String externalId) {
        return new PostRequest(Provider.FACEBOOK, externalId, "https://facebook.com/posts/" + externalId, null, null);
    }
}
