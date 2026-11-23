package com.huuhv.socialanalytics.config;

import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.support.converter.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AliasOnlyJsonMessageConverterTest {

    private static final String ALIAS = "sample";

    private final AliasOnlyJsonMessageConverter converter =
            new AliasOnlyJsonMessageConverter(JsonMapper.builder().build(), Map.of(ALIAS, SampleMessage.class));

    @Mock
    private Session session;

    @Test
    void writesRegisteredTypeAsJsonTextMessageWithAlias() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(session.createTextMessage(anyString())).thenReturn(message);

        converter.toMessage(new SampleMessage(42L, Instant.parse("2026-11-10T01:02:03Z")), session);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(session).createTextMessage(json.capture());
        assertThat(json.getValue()).contains("\"id\":42").contains("2026-11-10T01:02:03Z");
        // Alias, không phải tên class đầy đủ
        verify(message).setStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY, ALIAS);
        // Không bao giờ dùng ObjectMessage (Java serialization)
        verify(session, never()).createObjectMessage(any());
    }

    @Test
    void readsJsonBackIntoRegisteredType() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY)).thenReturn(ALIAS);
        when(message.getText()).thenReturn("{\"id\":7,\"occurredAt\":\"2026-11-10T01:02:03Z\"}");

        Object payload = converter.fromMessage(message);

        assertThat(payload).isEqualTo(new SampleMessage(7L, Instant.parse("2026-11-10T01:02:03Z")));
    }

    @Test
    void rejectsSendingUnregisteredType() {
        assertThatThrownBy(() -> converter.toMessage(new UnregisteredMessage("x"), session))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageContaining(UnregisteredMessage.class.getName());
    }

    @Test
    void rejectsClassNameAsTypeIdInsteadOfLoadingIt() throws Exception {
        // Message giả mạo ghi tên class đầy đủ: converter gốc của Spring sẽ Class.forName + deserialize vào class đó
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY))
                .thenReturn(SampleMessage.class.getName());

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class);
        verify(message, never()).getText();
    }

    @Test
    void conversionErrorDoesNotEchoSenderControlledDataIntoException() throws Exception {
        // _type do bên gửi đặt, chứa CR/LF để giả 1 dòng log — thông điệp gốc của Spring nối nguyên giá trị này
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY))
                .thenReturn("evil\r\n2026-11-12 INFO fake log line");
        when(message.getJMSMessageID()).thenReturn("ID:abc\r\ninjected");

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class)
                .hasNoCause()
                .hasMessageContaining("ID:abc")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("evil", "fake log line", "\r", "\n"));
    }

    @Test
    void malformedJsonErrorDoesNotEchoBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY)).thenReturn(ALIAS);
        when(message.getText()).thenReturn("{\"id\": secret-token-value");

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token-value"));
    }

    @Test
    void rejectsMessageWithoutTypeId() throws Exception {
        TextMessage message = mock(TextMessage.class);

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class);
    }

    record SampleMessage(Long id, Instant occurredAt) {
    }

    record UnregisteredMessage(String value) {
    }
}
