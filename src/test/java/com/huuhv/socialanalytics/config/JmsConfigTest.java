package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cấu hình JMS thật của Boot + {@link JmsConfig} trên broker Artemis embedded (không cần Docker/MySQL).
 */
class JmsConfigTest {

    private static final String TEST_QUEUE = "jms-config-test";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, ArtemisAutoConfiguration.class, JmsAutoConfiguration.class))
            .withUserConfiguration(JmsConfig.class)
            .withPropertyValues(
                    "spring.artemis.mode=embedded",
                    "spring.artemis.embedded.persistent=false",
                    "spring.jms.listener.session.transacted=true",
                    "spring.jms.template.receive-timeout=5s");

    @Test
    void jmsTemplateAndListenerFactoryUseAliasOnlyJsonConverter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JmsTemplate.class).getMessageConverter())
                    .isInstanceOf(AliasOnlyJsonMessageConverter.class);

            DefaultJmsListenerContainerFactory factory = context.getBean(DefaultJmsListenerContainerFactory.class);
            DefaultMessageListenerContainer container = factory.createListenerContainer(endpoint());
            assertThat(container.getMessageConverter()).isInstanceOf(AliasOnlyJsonMessageConverter.class);
            // Listener lỗi → rollback session → broker giao lại message (cơ sở cho retry + DLQ)
            assertThat(container.isSessionTransacted()).isTrue();
            // Factory của app thay bean của Boot: vẫn nhận spring.jms.listener.* qua configurer, thêm ErrorHandler
            assertThat(container.getErrorHandler()).isInstanceOf(JmsListenerErrorHandler.class);
        });
    }

    @Test
    void messageWithUnknownTypeIdIsNotDeserialized() {
        contextRunner.run(context -> {
            JmsTemplate jmsTemplate = context.getBean(JmsTemplate.class);
            // Message do bên ngoài gửi vào queue, ghi tên class tuỳ ý vào _type
            jmsTemplate.send(TEST_QUEUE, session -> {
                var message = session.createTextMessage("{}");
                message.setStringProperty(AliasOnlyJsonMessageConverter.TYPE_ID_PROPERTY, "java.util.HashMap");
                return message;
            });

            assertThatThrownBy(() -> jmsTemplate.receiveAndConvert(TEST_QUEUE))
                    .isInstanceOf(MessageConversionException.class)
                    // Tên class do bên gửi đặt không lọt vào thông điệp lỗi (log injection)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("java.util.HashMap"));
        });
    }

    private static SimpleJmsListenerEndpoint endpoint() {
        var endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("jms-config-test-endpoint");
        endpoint.setDestination(TEST_QUEUE);
        endpoint.setMessageListener(message -> {
        });
        return endpoint;
    }
}
