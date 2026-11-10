package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.OAuth2UserAttributes;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client gọi provider OAuth2 (Facebook, X) trong login callback:
 * <ul>
 *     <li>{@code providerUserInfoService}: gọi user-info-uri, dùng làm delegate cho {@code CustomOAuth2UserService}.
 *     Không bị Spring Security tự chọn làm user service: {@code SecurityConfig} khai báo tường minh
 *     {@code userInfoEndpoint().userService(customOAuth2UserService)}.</li>
 *     <li>{@code authorizationCodeTokenResponseClient}: đổi authorization code lấy access token. {@code oauth2Login}
 *     tự lấy bean kiểu {@code OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>} từ context.</li>
 * </ul>
 * Cả hai giữ nguyên cấu hình mặc định của Spring Security (message converter, error handler dịch lỗi OAuth2) và chỉ
 * thêm connect/read timeout từ {@code app.oauth2.*} (OWASP A10). Hết giờ → {@code OAuth2AuthenticationException} →
 * {@code /login?error}.
 * <p>
 * Lúc khởi động kiểm tra mọi registration đều có client secret: Boot chỉ chặn {@code client-id} rỗng, còn secret rỗng
 * (vd biến môi trường để trống trong {@code .env}) khiến app chạy bình thường nhưng mọi lần đăng nhập lỗi
 * {@code invalid_client}. App chỉ dùng confidential client nên secret là bắt buộc (fail fast).
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    DefaultOAuth2UserService providerUserInfoService(AppProperties properties) {
        return userInfoService(properties.oauth2());
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(
            AppProperties properties) {
        return tokenResponseClient(properties.oauth2());
    }

    @Bean
    InitializingBean oauth2ClientSecretsValidator(OAuth2ClientProperties properties) {
        return () -> requireClientSecrets(properties.getRegistration());
    }

    static DefaultOAuth2UserService userInfoService(AppProperties.OAuth2Client settings) {
        // Giống RestTemplate mặc định của DefaultOAuth2UserService, thêm timeout
        RestTemplate restTemplate = new RestTemplate(requestFactory(settings));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setRestOperations(restTemplate);
        service.setAttributesConverter(OAuth2UserAttributes::converterFor);
        return service;
    }

    static RestClientAuthorizationCodeTokenResponseClient tokenResponseClient(AppProperties.OAuth2Client settings) {
        // Giống RestClient mặc định của AbstractRestClientOAuth2AccessTokenResponseClient, thêm timeout
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory(settings))
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    private static ClientHttpRequestFactory requestFactory(AppProperties.OAuth2Client settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.connectTimeout());
        factory.setReadTimeout(settings.readTimeout());
        return factory;
    }

    /** Thông báo lỗi chỉ nêu tên registration, không in giá trị cấu hình. */
    static void requireClientSecrets(Map<String, OAuth2ClientProperties.Registration> registrations) {
        List<String> missing = registrations.entrySet().stream()
                .filter(entry -> !StringUtils.hasText(entry.getValue().getClientSecret()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("OAuth2 client secret must be set for registration(s) " + missing
                    + " (spring.security.oauth2.client.registration.<id>.client-secret, "
                    + "env FACEBOOK_CLIENT_SECRET / TWITTER_CLIENT_SECRET)");
        }
    }
}
