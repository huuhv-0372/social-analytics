package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.service.PostUpsertService;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Luồng thật: transaction ghi bài viết + lịch sử import trên MySQL Testcontainers → {@code AFTER_COMMIT} → message trên
 * broker Artemis embedded (profile test). Đọc message trực tiếp từ queue bằng {@link JmsTemplate} riêng có timeout.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ImportCompletedPublishIntegrationTest {

    private static final long RECEIVE_TIMEOUT_MS = 5_000;
    private static final long NO_MESSAGE_TIMEOUT_MS = 1_000;

    @Autowired
    private PostUpsertService postUpsertService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private ImportHistoryRepository importHistoryRepository;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private MessageConverter messageConverter;

    private JmsTemplate receiver;
    private User owner;

    @BeforeEach
    void setUp() {
        receiver = new JmsTemplate(connectionFactory);
        receiver.setMessageConverter(messageConverter);
        drainQueue();
        owner = userRepository.save(User.builder().displayName("Importer").build());
    }

    @AfterEach
    void cleanUp() {
        drainQueue();
        postRepository.deleteAll();
        importHistoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void publishesMessageAfterImportTransactionCommits() {
        PostUpsertService.Result result = postUpsertService.upsert(owner, List.of(
                post("fb-1"), post("fb-2")), 3);

        Object payload = receive(RECEIVE_TIMEOUT_MS);

        assertThat(payload).isInstanceOfSatisfying(ImportCompletedMessage.class, message -> {
            assertThat(message.importId()).isEqualTo(result.importId());
            assertThat(message.userId()).isEqualTo(owner.getId());
            assertThat(message.totalRows()).isEqualTo(3);
            assertThat(message.successRows()).isEqualTo(2);
            assertThat(message.failedRows()).isEqualTo(1);
            assertThat(message.occurredAt()).isNotNull();
        });
        // Listener (5.3) đọc lịch sử theo importId: bản ghi đã commit khi message tới
        assertThat(importHistoryRepository.findById(result.importId())).get()
                .extracting(ImportHistory::getStatus).isEqualTo(ImportStatus.PENDING);
    }

    @Test
    void rolledBackImportPublishesNothing() {
        transactionTemplate.executeWithoutResult(status -> {
            postUpsertService.upsert(owner, List.of(post("fb-1")), 1);
            status.setRollbackOnly();
        });

        assertThat(receive(NO_MESSAGE_TIMEOUT_MS)).isNull();
        assertThat(importHistoryRepository.count()).isZero();
        assertThat(postRepository.count()).isZero();
    }

    private Object receive(long timeoutMs) {
        receiver.setReceiveTimeout(timeoutMs);
        return receiver.receiveAndConvert(QueueNames.IMPORT_COMPLETED);
    }

    private void drainQueue() {
        receiver.setReceiveTimeout(JmsTemplate.RECEIVE_TIMEOUT_NO_WAIT);
        while (receiver.receive(QueueNames.IMPORT_COMPLETED) != null) {
            // bỏ message còn sót của test trước
        }
    }

    private static PostRequest post(String externalId) {
        return new PostRequest(Provider.FACEBOOK, externalId, "https://facebook.com/posts/" + externalId, null, null);
    }
}
