package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.messaging.MessageTypes;
import com.huuhv.socialanalytics.messaging.TopicNames;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
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

    /**
     * Topic {@value TopicNames#DASHBOARD_EVENTS} (address multicast trên Artemis). Producer gửi tới object
     * {@link Topic} này thay vì tên chuỗi vì {@code JmsTemplate} của Boot coi tên chuỗi là queue
     * ({@code spring.jms.pub-sub-domain=false} — giữ nguyên cho các queue).
     */
    @Bean
    Topic dashboardEventsTopic() {
        return ActiveMQJMSClient.createTopic(TopicNames.DASHBOARD_EVENTS);
    }

    /**
     * Factory cho listener topic — không qua configurer của Boot ({@code spring.jms.listener.*} là cấu hình của queue):
     * <ul>
     *     <li>pub/sub, subscription <b>non-durable</b>: mỗi instance đang chạy nhận 1 bản; instance tắt thì không giữ
     *     message cho nó (event cũ vô nghĩa, client tải lại khi kết nối lại).</li>
     *     <li>Đúng 1 consumer: subscription non-durable không chia sẻ — 2 consumer trong cùng instance sẽ nhận trùng.</li>
     *     <li>Không transacted: event lỗi không cần broker giao lại / DLQ.</li>
     *     <li>Cache level mặc định khi không transacted ({@code CACHE_CONSUMER}) giữ nguyên consumer giữa các lần
     *     receive — subscription non-durable không bị huỷ/tạo lại (mất message ở khoảng giữa).</li>
     * </ul>
     */
    @Bean(name = TopicNames.LISTENER_CONTAINER_FACTORY)
    DefaultJmsListenerContainerFactory topicListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                     MessageConverter jmsMessageConverter) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setMessageConverter(jmsMessageConverter);
        factory.setPubSubDomain(true);
        factory.setSubscriptionDurable(false);
        factory.setSessionTransacted(false);
        factory.setConcurrency("1");
        factory.setErrorHandler(new JmsListenerErrorHandler());
        return factory;
    }
}
