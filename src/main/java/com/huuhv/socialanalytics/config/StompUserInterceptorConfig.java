package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Gắn {@link StompUserIdChannelInterceptor} vào {@code clientInboundChannel} <b>trước</b> interceptor của Spring
 * Security ({@code WebSocketMessageBrokerSecurityConfiguration} có {@code @Order(HIGHEST_PRECEDENCE + 100)}) — cách
 * tài liệu Spring khuyến nghị: configurer riêng với {@code @Order(HIGHEST_PRECEDENCE + 99)}. Nhờ vậy
 * {@code SecurityContext} và luật phân quyền frame nhận principal đã đổi tên.
 */
@Configuration(proxyBeanMethods = false)
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class StompUserInterceptorConfig implements WebSocketMessageBrokerConfigurer {

    private final CurrentUserService currentUserService;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompUserIdChannelInterceptor(currentUserService));
    }
}
