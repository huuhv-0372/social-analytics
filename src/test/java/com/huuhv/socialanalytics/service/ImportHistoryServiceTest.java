package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportHistoryServiceTest {

    // Server +07: thời điểm ghi DB là giờ UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Mock
    private ImportHistoryRepository importHistoryRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ImportHistoryService importHistoryService;

    private final User owner = User.builder().id(7L).displayName("Owner").build();

    @BeforeEach
    void setUp() {
        importHistoryService = new ImportHistoryService(importHistoryRepository, eventPublisher, CLOCK);
    }

    @Test
    void marksDeadLetteredAtCurrentUtcTime() {
        when(importHistoryRepository.markDeadLettered(55L, LocalDateTime.of(2026, 11, 10, 4, 0))).thenReturn(1);

        assertThat(importHistoryService.markDeadLettered(55L)).isTrue();
    }

    @Test
    void importNotPendingIsNotMarkedDeadLettered() {
        when(importHistoryRepository.markDeadLettered(55L, LocalDateTime.of(2026, 11, 10, 4, 0))).thenReturn(0);

        assertThat(importHistoryService.markDeadLettered(55L)).isFalse();
    }

    @Test
    void recordsPendingHistoryAndPublishesEventWithCounts() {
        when(importHistoryRepository.save(any(ImportHistory.class))).thenAnswer(invocation -> {
            ImportHistory history = invocation.getArgument(0);
            history.setId(55L);
            // created_at do @PrePersist gán (giờ UTC)
            ReflectionTestUtils.setField(history, "createdAt", LocalDateTime.of(2026, 11, 10, 3, 4, 5));
            return history;
        });

        long importId = importHistoryService.record(owner, 10, 6, 3, 1);

        assertThat(importId).isEqualTo(55L);
        ArgumentCaptor<ImportHistory> saved = ArgumentCaptor.forClass(ImportHistory.class);
        verify(importHistoryRepository).save(saved.capture());
        assertThat(saved.getValue()).satisfies(history -> {
            assertThat(history.getUser()).isSameAs(owner);
            assertThat(history.getTotalRows()).isEqualTo(10);
            assertThat(history.getCreatedRows()).isEqualTo(6);
            assertThat(history.getUpdatedRows()).isEqualTo(3);
            assertThat(history.getFailedRows()).isEqualTo(1);
            assertThat(history.getStatus()).isEqualTo(ImportStatus.PENDING);
        });
        verify(eventPublisher).publishEvent(
                new ImportCompletedEvent(55L, 7L, 10, 9, 1, Instant.parse("2026-11-10T03:04:05Z")));
    }

    @Test
    void marksProcessedOnlyWhenRowWasUpdated() {
        LocalDateTime processedAt = LocalDateTime.of(2026, 11, 10, 4, 0);
        when(importHistoryRepository.markProcessed(55L, processedAt)).thenReturn(1);
        when(importHistoryRepository.markProcessed(56L, processedAt)).thenReturn(0);

        assertThat(importHistoryService.markProcessed(55L, processedAt)).isTrue();
        assertThat(importHistoryService.markProcessed(56L, processedAt)).isFalse();
    }

    @Test
    void findsPendingImportsNotTouchedSinceMinAge() {
        // Cutoff = bây giờ (UTC) - minAge
        when(importHistoryRepository.findPendingIdsUpdatedBefore(LocalDateTime.of(2026, 11, 10, 3, 50), Limit.of(20)))
                .thenReturn(List.of(3L, 4L));

        assertThat(importHistoryService.findPendingIdsToResend(Duration.ofMinutes(10), 20)).containsExactly(3L, 4L);
    }

    @Test
    void republishesPendingImportWithCountsFromDatabase() {
        when(importHistoryRepository.touchPending(55L, LocalDateTime.of(2026, 11, 10, 4, 0))).thenReturn(1);
        when(importHistoryRepository.findCountsById(55L)).thenReturn(
                Optional.of(new ImportCounts(7L, 10, 9, 1, LocalDateTime.of(2026, 11, 10, 3, 4, 5))));

        assertThat(importHistoryService.republishPending(55L)).isTrue();

        // Cùng sự kiện như lúc import → producer gửi message sau khi transaction commit
        verify(eventPublisher).publishEvent(
                new ImportCompletedEvent(55L, 7L, 10, 9, 1, Instant.parse("2026-11-10T03:04:05Z")));
    }

    @Test
    void importNoLongerPendingIsNotRepublished() {
        // Listener đã xử lý (PROCESSED) hoặc đã vào DLQ trong lúc chờ
        when(importHistoryRepository.touchPending(55L, LocalDateTime.of(2026, 11, 10, 4, 0))).thenReturn(0);

        assertThat(importHistoryService.republishPending(55L)).isFalse();

        verify(importHistoryRepository, never()).findCountsById(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordsImportWhereEveryRowFailed() {
        when(importHistoryRepository.save(any(ImportHistory.class))).thenAnswer(invocation -> {
            ImportHistory history = invocation.getArgument(0);
            history.setId(56L);
            return history;
        });

        importHistoryService.record(owner, 4, 0, 0, 4);

        ArgumentCaptor<ImportCompletedEvent> event = ArgumentCaptor.forClass(ImportCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().successRows()).isZero();
        assertThat(event.getValue().failedRows()).isEqualTo(4);
    }
}
