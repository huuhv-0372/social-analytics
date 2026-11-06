package com.huuhv.socialanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gọi user-info-uri qua {@link MockRestServiceServer} — không gọi Facebook / X thật.
 */
class CustomOAuth2UserServiceTest {

    private static final String X_USER_INFO_URI = "https://api.x.com/2/users/me";
    private static final String FACEBOOK_USER_INFO_URI = "https://graph.facebook.com/me?fields=id,name,email";

    private MockRestServiceServer server;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        delegate.setRestOperations(restTemplate);
        service = new CustomOAuth2UserService(delegate);
    }

    @Test
    void xUserInfoIsUnwrappedFromDataField() {
        server.expect(requestTo(X_USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer x-access-token"))
                .andRespond(withSuccess("""
                        {"data":{"id":"2244994945","name":"Dev User","username":"devuser"}}""",
                        MediaType.APPLICATION_JSON));

        OAuth2User user = service.loadUser(request("twitter", X_USER_INFO_URI, "x-access-token"));

        assertThat(user.getName()).isEqualTo("2244994945");
        assertThat(user.<String>getAttribute("name")).isEqualTo("Dev User");
        assertThat(user.<String>getAttribute("username")).isEqualTo("devuser");
        assertThat(user.getAttributes()).doesNotContainKey("data");
        server.verify();
    }

    @Test
    void xUserInfoWithoutIdIsRejected() {
        server.expect(requestTo(X_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"errors":[{"title":"Unauthorized"}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.loadUser(request("twitter", X_USER_INFO_URI, "x-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> assertThat(ex.getError().getErrorCode())
                        .isEqualTo(CustomOAuth2UserService.INVALID_USER_INFO_RESPONSE));
    }

    @Test
    void xUserInfoWithBlankIdIsRejected() {
        server.expect(requestTo(X_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"data":{"id":" ","name":"Dev User"}}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.loadUser(request("twitter", X_USER_INFO_URI, "x-access-token")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void facebookUserInfoIsKeptAsIs() {
        server.expect(requestTo(FACEBOOK_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"id":"1234567890","name":"Dev User","email":"dev@example.com"}""",
                        MediaType.APPLICATION_JSON));

        OAuth2User user = service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token"));

        assertThat(user.getName()).isEqualTo("1234567890");
        assertThat(user.<String>getAttribute("email")).isEqualTo("dev@example.com");
    }

    private static OAuth2UserRequest request(String registrationId, String userInfoUri, String tokenValue) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .userInfoUri(userInfoUri)
                .userNameAttributeName("id")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
                Instant.now(), Instant.now().plusSeconds(3600));
        return new OAuth2UserRequest(registration, token);
    }
}
