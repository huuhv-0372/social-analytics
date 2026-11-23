package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.jms.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class JmsListenerErrorHandlerTest {

    private final JmsListenerErrorHandler handler = new JmsListenerErrorHandler();

    @Test
    void conversionFailureIsLoggedAsSingleLineWithoutStackTrace(CapturedOutput output) {
        // Dạng exception container nhận được khi tham số @JmsListener không chuyển đổi được
        handler.handleError(new ListenerExecutionFailedException("Listener method threw exception",
                new MessageConversionException("Cannot convert JMS message ID:1 (MessageConversionException)")));

        assertThat(output).contains("JMS message could not be converted")
                .contains("Cannot convert JMS message ID:1")
                .doesNotContain("ListenerExecutionFailedException")
                .doesNotContain("\tat ");
    }

    @Test
    void otherListenerFailureKeepsStackTraceForDebugging(CapturedOutput output) {
        handler.handleError(new ListenerExecutionFailedException("Listener method threw exception",
                new QueryTimeoutException("db down")));

        assertThat(output).contains("JMS listener failed, broker will redeliver the message")
                .contains("QueryTimeoutException")
                .contains("\tat ");
    }
}
