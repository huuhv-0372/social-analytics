package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportHistoryService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ImportCompletedDlqListenerTest {

    @Mock
    private MessageConverter messageConverter;

    @Mock
    private ImportHistoryService importHistoryService;

    @Mock
    private Message message;

    @InjectMocks
    private ImportCompletedDlqListener listener;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void marksImportDeadLetteredWithImportIdInMdc() throws Exception {
        when(messageConverter.fromMessage(message)).thenReturn(payload(12L));
        AtomicReference<String> mdcDuringMark = new AtomicReference<>();
        when(importHistoryService.markDeadLettered(12L)).thenAnswer(invocation -> {
            mdcDuringMark.set(MDC.get(ImportCompletedListener.MDC_IMPORT_ID));
            return true;
        });

        listener.onDeadLetter(message);

        verify(importHistoryService).markDeadLettered(12L);
        assertThat(mdcDuringMark).hasValue("12");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void importAlreadyProcessedIsAcknowledgedWithoutError() throws Exception {
        when(messageConverter.fromMessage(message)).thenReturn(payload(12L));
        when(importHistoryService.markDeadLettered(12L)).thenReturn(false);

        assertThatCode(() -> listener.onDeadLetter(message)).doesNotThrowAnyException();
    }

    @Test
    void unreadablePayloadIsOnlyLogged() throws Exception {
        // Message vào DLQ vì chính payload không đọc được (vd _type lạ) → không ném lại, không đụng DB
        when(messageConverter.fromMessage(message))
                .thenThrow(new MessageConversionException("Unknown type id java.util.HashMap"));

        assertThatCode(() -> listener.onDeadLetter(message)).doesNotThrowAnyException();

        verifyNoInteractions(importHistoryService);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void unreadablePayloadLogsIdentifyingMetadataButNotTheBody(CapturedOutput output) throws Exception {
        TextMessage textMessage = mock(TextMessage.class);
        when(textMessage.getJMSMessageID()).thenReturn("ID:abc\r\nFAKE LOG LINE");
        when(textMessage.getJMSTimestamp()).thenReturn(Instant.parse("2026-11-10T03:04:05Z").toEpochMilli());
        when(textMessage.getText()).thenReturn("{\"secret\":\"payload-value\"}");
        when(messageConverter.fromMessage(textMessage))
                .thenThrow(new MessageConversionException("Unknown type id java.util.HashMap"));

        listener.onDeadLetter(textMessage);

        assertThat(output)
                .contains("Dead-lettered message is unreadable (MessageConversionException)")
                .contains("messageId=ID:abc")
                .contains("timestamp=2026-11-10T03:04:05Z")
                .contains("bodyLength=26")
                // Không log nội dung / thông điệp lỗi (có thể chứa dữ liệu message), không để CR/LF tách dòng log
                .doesNotContain("payload-value")
                .doesNotContain("java.util.HashMap")
                .doesNotContain("\nFAKE LOG LINE");
        verifyNoInteractions(importHistoryService);
    }

    @Test
    void jmsErrorWhileReadingIsOnlyLogged() throws Exception {
        when(messageConverter.fromMessage(message)).thenThrow(new JMSException("broken message"));

        assertThatCode(() -> listener.onDeadLetter(message)).doesNotThrowAnyException();
        verifyNoInteractions(importHistoryService);
    }

    @Test
    void payloadWithoutValidImportIdIsOnlyLogged() throws Exception {
        when(messageConverter.fromMessage(message)).thenReturn(payload(null));

        listener.onDeadLetter(message);

        verifyNoInteractions(importHistoryService);
    }

    @Test
    void databaseErrorPropagatesSoDeadLetterIsRedeliveredInsteadOfLost() throws Exception {
        when(messageConverter.fromMessage(message)).thenReturn(payload(12L));
        when(importHistoryService.markDeadLettered(12L)).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> listener.onDeadLetter(message)).isInstanceOf(QueryTimeoutException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static ImportCompletedMessage payload(Long importId) {
        return new ImportCompletedMessage(importId, 3L, 5, 4, 1, Instant.parse("2026-11-10T03:04:05Z"));
    }
}
