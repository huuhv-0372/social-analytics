package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.messaging.MessageTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

/**
 * JMS trên ActiveMQ Artemis. Kết nối broker do Boot cấu hình ({@code spring.artemis.*}): {@code mode=native} tới
 * broker riêng (production, docker compose), {@code mode=embedded} chạy broker trong JVM (dev, test).
 * <p>
 * Bean {@link MessageConverter} dưới đây được Boot gắn vào cả {@code JmsTemplate} lẫn listener container factory
 * mặc định của {@code @JmsListener}. Listener chạy với session transacted ({@code spring.jms.listener.session.transacted}):
 * listener ném exception → message được broker giao lại.
 */
@Configuration(proxyBeanMethods = false)
public class JmsConfig {

    /** JSON qua {@code TextMessage}, kiểu payload chỉ theo alias khai báo ở {@link MessageTypes}. */
    @Bean
    MessageConverter jmsMessageConverter(JsonMapper jsonMapper) {
        return new AliasOnlyJsonMessageConverter(jsonMapper, MessageTypes.TYPE_ID_MAPPINGS);
    }
}
