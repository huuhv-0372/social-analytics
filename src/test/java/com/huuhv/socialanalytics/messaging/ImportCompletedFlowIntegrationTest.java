package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.UserImportStatisticRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.scheduler.ResendImportCompletedJob;
import com.huuhv.socialanalytics.service.ImportHistoryService;
import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.service.PostUpsertService;
import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Luồng thật: transaction import (MySQL Testcontainers) → {@code AFTER_COMMIT} → message trên broker Artemis embedded
 * (profile test) → {@code ImportCompletedListener} → thống kê tổng hợp. Listener chạy trên thread của container JMS
 * nên kiểm tra bằng chờ có giới hạn (Awaitility / {@code Mockito.timeout}), không {@code sleep}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ImportCompletedFlowIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long WAIT_MS = WAIT.toMillis();

    @Autowired
    private PostUpsertService postUpsertService;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @Autowired
    private ResendImportCompletedJob resendImportCompletedJob;

    @MockitoSpyBean
    private ImportStatisticsService importStatisticsService;

    // Không dùng trong class này: khai báo cùng bộ spy với ImportCompletedDlqIntegrationTest để 2 class dùng chung
    // context đã cache (bean override là một phần của khoá cache)
    @MockitoSpyBean
    private ImportHistoryService importHistoryService;

    // Cùng bộ spy với ImportCompletedDlqIntegrationTest / DashboardEventFanOutIntegrationTest (dùng chung context)
    @MockitoSpyBean
    private DashboardEventBroadcaster dashboardEventBroadcaster;

    private User owner;
    private Instant testStartedAt;

    @BeforeEach
    void setUp() {
        testStartedAt = Instant.now();
        owner = userRepository.save(User.builder().displayName("Importer").build());
    }

    @AfterEach
    void cleanUp() {
        postRepository.deleteAll();
        importHistoryRepository.deleteAll();
        // user_import_statistics xoá theo user (ON DELETE CASCADE)
        userRepository.deleteAll();
    }

    @Test
    void committedImportIsAddedToOwnerStatisticsAsynchronously() {
        long importId = postUpsertService.saveImport(owner, List.of(post("fb-1"), post("fb-2")), 3).importId();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(userImportStatisticRepository.findById(owner.getId())).get().satisfies(statistic -> {
                    assertThat(statistic.getTotalImports()).isEqualTo(1);
                    assertThat(statistic.getTotalRows()).isEqualTo(3);
                    assertThat(statistic.getSuccessRows()).isEqualTo(2);
                    assertThat(statistic.getFailedRows()).isEqualTo(1);
                }));
        assertThat(importHistoryRepository.findById(importId)).get().satisfies(history -> {
            assertThat(history.getStatus()).isEqualTo(ImportStatus.PROCESSED);
            assertThat(history.getProcessedAt()).isNotNull();
        });
        verify(importStatisticsService).applyImport(importId, owner.getId());
        // Sau khi thống kê commit: event IMPORT_PROCESSED đi qua JMS topic tới WebSocket của instance này
        verify(dashboardEventBroadcaster, timeout(WAIT_MS))
                .broadcast(eq(DashboardEventType.IMPORT_PROCESSED), occurredInThisTest());
    }

    @Test
    void redeliveredMessageDoesNotCountImportTwice() {
        long importId = postUpsertService.saveImport(owner, List.of(post("fb-1")), 1).importId();
        verify(importStatisticsService, timeout(WAIT_MS)).applyImport(importId, owner.getId());
        awaitProcessed(importId);

        // Gửi lại đúng message đó (mô phỏng broker giao lại / producer gửi trùng)
        jmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED,
                new ImportCompletedMessage(importId, owner.getId(), 1, 1, 0, Instant.now()));

        verify(importStatisticsService, timeout(WAIT_MS).times(2)).applyImport(importId, owner.getId());
        assertThat(userImportStatisticRepository.findById(owner.getId())).get()
                .extracting(UserImportStatistic::getTotalImports, UserImportStatistic::getTotalRows)
                .containsExactly(1L, 1L);
        // Message trùng không cộng gì → không có event thứ 2
        verify(dashboardEventBroadcaster, after(1_000).times(1)).broadcast(any(), occurredInThisTest());
    }

    @Test
    void rolledBackImportReachesNoListener() {
        transactionTemplate.executeWithoutResult(status -> {
            postUpsertService.saveImport(owner, List.of(post("fb-1")), 1);
            status.setRollbackOnly();
        });

        verify(importStatisticsService, after(1_500).never()).applyImport(anyLong(), any());
        verify(dashboardEventBroadcaster, never()).broadcast(any(), occurredInThisTest());
        assertThat(importHistoryRepository.count()).isZero();
        assertThat(userImportStatisticRepository.findById(owner.getId())).isEmpty();
    }

    @Test
    void messageForUnknownImportChangesNothing() {
        jmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED,
                new ImportCompletedMessage(Long.MAX_VALUE, owner.getId(), 9, 9, 0, Instant.now()));

        verify(importStatisticsService, timeout(WAIT_MS)).applyImport(Long.MAX_VALUE, owner.getId());
        verify(importStatisticsService, times(1)).applyImport(anyLong(), any());
        assertThat(userImportStatisticRepository.findById(owner.getId())).isEmpty();
    }

    @Test
    void lostMessageIsResentByJobAndCountedOnce() {
        // Mô phỏng message bị mất (broker chết lúc publish): lịch sử PENDING đã ghi nhưng không có message nào,
        // lần ghi cuối đã quá min-age (10 phút)
        long importId = transactionTemplate.execute(status -> {
            ImportHistory history = importHistoryRepository.save(ImportHistory.builder().user(owner)
                    .totalRows(4).createdRows(2).updatedRows(1).failedRows(1).status(ImportStatus.PENDING).build());
            importHistoryRepository.touchPending(history.getId(), LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
            return history.getId();
        });

        assertThat(resendImportCompletedJob.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(1, 1, 0));

        awaitProcessed(importId);
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(userImportStatisticRepository.findById(owner.getId())).get()
                        .extracting(UserImportStatistic::getTotalImports, UserImportStatistic::getSuccessRows,
                                UserImportStatistic::getFailedRows)
                        .containsExactly(1L, 3L, 1L));
        // Đã PROCESSED → lần chạy sau không gửi lại
        assertThat(resendImportCompletedJob.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(0, 0, 0));
    }

    /**
     * Event của test trước có thể tới spy sau khi test đó kết thúc (gửi sau commit, đi qua JMS) — {@code occurredAt}
     * được gán trong transaction, trước lúc test trước thấy dữ liệu đã commit, nên luôn sớm hơn lúc test này bắt đầu.
     */
    private Instant occurredInThisTest() {
        return argThat(occurredAt -> occurredAt != null && !occurredAt.isBefore(testStartedAt));
    }

    private void awaitProcessed(long importId) {
        await().atMost(WAIT).until(() -> importHistoryRepository.findById(importId)
                .map(ImportHistory::getStatus).orElseThrow() == ImportStatus.PROCESSED);
    }

    private static PostRequest post(String externalId) {
        return new PostRequest(Provider.FACEBOOK, externalId, "https://facebook.com/posts/" + externalId, null, null);
    }
}
