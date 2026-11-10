package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client gọi provider OAuth2 có timeout và vẫn giữ cấu hình mặc định của Spring Security (parse token response, dịch
 * lỗi) — gọi server HTTP cục bộ, không gọi Facebook / X thật.
 */
class OAuth2ClientConfigTest {

    private static final AppProperties.OAuth2Client SHORT_TIMEOUTS =
            new AppProperties.OAuth2Client(Duration.ofSeconds(1), Duration.ofMillis(300));

    private HttpServer httpServer;
    private ServerSocket silentServer;

    @AfterEach
    void stopServers() throws IOException {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (silentServer != null) {
            silentServer.close();
        }
    }

    @Test
    void tokenResponseClientParsesProviderResponse() throws IOException {
        String tokenUri = serveJson("/token", """
                {"access_token":"provider-token","token_type":"Bearer","expires_in":3600}""");

        OAuth2AccessTokenResponse response = OAuth2ClientConfig.tokenResponseClient(SHORT_TIMEOUTS)
                .getTokenResponse(grantRequest(tokenUri));

        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("provider-token");
    }

    @Test
    void tokenResponseClientGivesUpWhenProviderHangs() throws IOException {
        String tokenUri = silentUri("/token");
        long start = System.nanoTime();

        assertThatThrownBy(() -> OAuth2ClientConfig.tokenResponseClient(SHORT_TIMEOUTS)
                .getTokenResponse(grantRequest(tokenUri)))
                .isInstanceOf(OAuth2AuthorizationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void userInfoServiceLoadsUserAndKeepsAttributeConverter() throws IOException {
        String userInfoUri = serveJson("/me", """
                {"data":{"id":"2244994945","username":"devuser"}}""");

        OAuth2User user = OAuth2ClientConfig.userInfoService(SHORT_TIMEOUTS)
                .loadUser(userRequest("twitter", userInfoUri));

        // Converter của X (bóc "data") vẫn được gắn
        assertThat(user.getName()).isEqualTo("2244994945");
    }

    @Test
    void userInfoServiceGivesUpWhenProviderHangs() throws IOException {
        String userInfoUri = silentUri("/me");
        DefaultOAuth2UserService service = OAuth2ClientConfig.userInfoService(SHORT_TIMEOUTS);
        long start = System.nanoTime();

        assertThatThrownBy(() -> service.loadUser(userRequest("facebook", userInfoUri)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void blankClientSecretFailsStartupWithoutPrintingValues() {
        Map<String, OAuth2ClientProperties.Registration> registrations = new LinkedHashMap<>();
        registrations.put("facebook", registration("fb-client-id", "fb-secret-value"));
        registrations.put("twitter", registration("x-client-id", " "));

        assertThatThrownBy(() -> OAuth2ClientConfig.requireClientSecrets(registrations))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[twitter]")
                .hasMessageNotContaining("facebook")
                .hasMessageNotContaining("fb-secret-value")
                .hasMessageNotContaining("x-client-id");
    }

    @Test
    void missingClientSecretsAreAllReported() {
        Map<String, OAuth2ClientProperties.Registration> registrations = new LinkedHashMap<>();
        registrations.put("twitter", registration("x-client-id", null));
        registrations.put("facebook", registration("fb-client-id", ""));

        assertThatThrownBy(() -> OAuth2ClientConfig.requireClientSecrets(registrations))
                .hasMessageContaining("[facebook, twitter]");
    }

    @Test
    void allClientSecretsPresentPassesValidation() {
        Map<String, OAuth2ClientProperties.Registration> registrations = Map.of(
                "facebook", registration("fb-client-id", "fb-secret"),
                "twitter", registration("x-client-id", "x-secret"));

        assertThatCode(() -> OAuth2ClientConfig.requireClientSecrets(registrations)).doesNotThrowAnyException();
    }

    private String serveJson(String path, String body) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + path;
    }

    /** Nhận kết nối (backlog của OS) nhưng không bao giờ trả lời — giả lập provider bị treo. */
    private String silentUri(String path) throws IOException {
        silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        return "http://127.0.0.1:" + silentServer.getLocalPort() + path;
    }

    private static OAuth2ClientProperties.Registration registration(String clientId, String clientSecret) {
        OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
        registration.setClientId(clientId);
        registration.setClientSecret(clientSecret);
        return registration;
    }

    private static ClientRegistration clientRegistration(String registrationId, String tokenUri, String userInfoUri) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri(tokenUri)
                .userInfoUri(userInfoUri)
                .userNameAttributeName("id")
                .build();
    }

    private static OAuth2AuthorizationCodeGrantRequest grantRequest(String tokenUri) {
        ClientRegistration registration = clientRegistration("facebook", tokenUri, "https://provider.example/me");
        String redirectUri = "https://app.example/login/oauth2/code/facebook";
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(redirectUri)
                .state("state")
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code")
                .redirectUri(redirectUri)
                .state("state")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private static OAuth2UserRequest userRequest(String registrationId, String userInfoUri) {
        ClientRegistration registration =
                clientRegistration(registrationId, "https://provider.example/token", userInfoUri);
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token",
                Instant.now(), Instant.now().plusSeconds(3600));
        return new OAuth2UserRequest(registration, token);
    }
}
