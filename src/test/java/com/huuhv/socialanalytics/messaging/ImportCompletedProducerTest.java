package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImportCompletedProducerTest {

    private static final ImportCompletedEvent EVENT =
            new ImportCompletedEvent(12L, 3L, 5, 4, 1, Instant.parse("2026-11-10T03:04:05Z"));

    @Mock
    private JmsTemplate jmsTemplate;

    @InjectMocks
    private ImportCompletedProducer producer;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void sendsMessageToImportCompletedQueueWithImportIdInMdc() {
        AtomicReference<String> mdcDuringSend = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringSend.set(MDC.get(ImportCompletedProducer.MDC_IMPORT_ID));
            return null;
        }).when(jmsTemplate).convertAndSend(eq(QueueNames.IMPORT_COMPLETED), any(Object.class));

        producer.onImportCompleted(EVENT);

        verify(jmsTemplate).convertAndSend(QueueNames.IMPORT_COMPLETED,
                new ImportCompletedMessage(12L, 3L, 5, 4, 1, Instant.parse("2026-11-10T03:04:05Z")));
        assertThat(mdcDuringSend).hasValue("12");
        assertThat(MDC.get(ImportCompletedProducer.MDC_IMPORT_ID)).isNull();
    }

    @Test
    void keepsOtherMdcKeysOfRequestThread() {
        MDC.put("requestKey", "kept");

        producer.onImportCompleted(EVENT);

        assertThat(MDC.get("requestKey")).isEqualTo("kept");
        assertThat(MDC.get(ImportCompletedProducer.MDC_IMPORT_ID)).isNull();
    }

    @Test
    void brokerFailureDoesNotPropagateBecauseImportIsAlreadyCommitted() {
        doThrow(new UncategorizedJmsException("broker down"))
                .when(jmsTemplate).convertAndSend(eq(QueueNames.IMPORT_COMPLETED), any(Object.class));

        assertThatCode(() -> producer.onImportCompleted(EVENT)).doesNotThrowAnyException();
        assertThat(MDC.get(ImportCompletedProducer.MDC_IMPORT_ID)).isNull();
    }
}
