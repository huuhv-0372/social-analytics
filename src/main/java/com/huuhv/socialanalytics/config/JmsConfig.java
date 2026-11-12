package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.messaging.MessageTypes;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

/**
 * JMS trên ActiveMQ Artemis. Kết nối broker do Boot cấu hình ({@code spring.artemis.*}): {@code mode=native} tới
 * broker riêng (production, docker compose), {@code mode=embedded} chạy broker trong JVM (dev, test).
 * <p>
 * Bean {@link MessageConverter} dưới đây được Boot gắn vào cả {@code JmsTemplate} lẫn listener container factory
 * của {@code @JmsListener}. Listener chạy với session transacted ({@code spring.jms.listener.session.transacted}):
 * listener ném exception → message được broker giao lại.
 */
@Configuration(proxyBeanMethods = false)
public class JmsConfig {

    /** JSON qua {@code TextMessage}, kiểu payload chỉ theo alias khai báo ở {@link MessageTypes}. */
    @Bean
    MessageConverter jmsMessageConverter(JsonMapper jsonMapper) {
        return new AliasOnlyJsonMessageConverter(jsonMapper, MessageTypes.TYPE_ID_MAPPINGS);
    }

    /**
     * Thay factory mặc định của Boot (cùng tên bean → Boot bỏ bean của nó) chỉ để gắn {@link JmsListenerErrorHandler}:
     * Boot 4 không tự lấy bean {@code ErrorHandler}. Phần còn lại giữ nguyên như Boot: cấu hình
     * {@code spring.jms.listener.*} + converter qua configurer, listener dùng {@code ConnectionFactory} gốc (không qua
     * {@code CachingConnectionFactory}).
     */
    @Bean
    DefaultJmsListenerContainerFactory jmsListenerContainerFactory(DefaultJmsListenerContainerFactoryConfigurer configurer,
                                                                   ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setErrorHandler(new JmsListenerErrorHandler());
        return factory;
    }
}
