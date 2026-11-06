package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.OAuth2UserAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;

/**
 * Client gọi user-info-uri của provider, dùng làm delegate cho {@code CustomOAuth2UserService}.
 * Không bị Spring Security tự chọn làm user service: {@code SecurityConfig} khai báo tường minh
 * {@code userInfoEndpoint().userService(customOAuth2UserService)}.
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    DefaultOAuth2UserService providerUserInfoService() {
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setAttributesConverter(OAuth2UserAttributes::converterFor);
        return service;
    }
}
