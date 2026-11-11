package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.service.ImportStatisticsService.ApplyResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportCompletedListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-10T03:04:05Z");

    @Mock
    private ImportStatisticsService importStatisticsService;

    @InjectMocks
    private ImportCompletedListener listener;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void appliesImportWithImportIdInMdcAndClearsMdcAfterwards() {
        AtomicReference<String> mdcDuringApply = new AtomicReference<>();
        when(importStatisticsService.applyImport(12L, 3L)).thenAnswer(invocation -> {
            mdcDuringApply.set(MDC.get(ImportCompletedListener.MDC_IMPORT_ID));
            return ApplyResult.APPLIED;
        });

        listener.onImportCompleted(message(12L, 3L));

        verify(importStatisticsService).applyImport(12L, 3L);
        assertThat(mdcDuringApply).hasValue("12");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void duplicateOrUnknownImportIsAcknowledgedWithoutError() {
        when(importStatisticsService.applyImport(12L, 3L)).thenReturn(ApplyResult.ALREADY_PROCESSED);
        listener.onImportCompleted(message(12L, 3L));

        when(importStatisticsService.applyImport(13L, 3L)).thenReturn(ApplyResult.NOT_FOUND);
        listener.onImportCompleted(message(13L, 3L));

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void invalidMessageIsDiscardedWithoutRetry(ImportCompletedMessage message) {
        // Không ném exception: retry message sai định dạng chỉ lặp lại cùng lỗi
        listener.onImportCompleted(message);

        verifyNoInteractions(importStatisticsService);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    static Stream<ImportCompletedMessage> invalidMessages() {
        return Stream.of(message(null, 3L), message(0L, 3L), message(-5L, 3L), message(12L, null),
                message(12L, 0L));
    }

    @Test
    void transientErrorPropagatesSoBrokerRedeliversMessage() {
        when(importStatisticsService.applyImport(12L, 3L)).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> listener.onImportCompleted(message(12L, 3L)))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static ImportCompletedMessage message(Long importId, Long userId) {
        return new ImportCompletedMessage(importId, userId, 5, 4, 1, OCCURRED_AT);
    }
}
