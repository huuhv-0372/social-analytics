package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gọi user-info-uri qua {@link MockRestServiceServer} — không gọi Facebook / X thật; lưu DB qua mock
 * {@link SocialAccountService}.
 */
class CustomOAuth2UserServiceTest {

    private static final String X_USER_INFO_URI = "https://api.x.com/2/users/me";
    private static final String FACEBOOK_USER_INFO_URI = "https://graph.facebook.com/me?fields=id,name,email";
    private static final User SAVED_USER = User.builder().id(7L).displayName("Dev User").build();

    private MockRestServiceServer server;
    private SocialAccountService socialAccountService;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        delegate.setRestOperations(restTemplate);
        // Giống bean providerUserInfoService ở config/OAuth2ClientConfig
        delegate.setAttributesConverter(OAuth2UserAttributes::converterFor);
        socialAccountService = mock(SocialAccountService.class);
        when(socialAccountService.recordLogin(any(), any(), any())).thenReturn(SAVED_USER);
        service = new CustomOAuth2UserService(delegate, socialAccountService);
    }

    @Test
    void principalCarriesInternalUserIdOfSavedAccount() {
        expectFacebookUserInfo();

        OAuth2User user = service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token"));

        // CurrentUserService lấy owner theo id này mà không query lại
        assertThat(user).isInstanceOfSatisfying(AppOAuth2User.class,
                principal -> {
                    assertThat(principal.getUserId()).isEqualTo(SAVED_USER.getId());
                    // Tên hiển thị đã chuẩn hoá lưu ở users.display_name, dùng cho layout
                    assertThat(principal.getDisplayName()).isEqualTo(SAVED_USER.getDisplayName());
                });
        assertThat(user.getName()).isEqualTo("1234567890");
        assertThat(user.<String>getAttribute("name")).isEqualTo("Dev User");
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
        // Lưu tài khoản X theo id đã bóc ra, kèm đúng access token của lần đăng nhập
        verify(socialAccountService).recordLogin(eq(Provider.TWITTER),
                argThat(saved -> "2244994945".equals(saved.getName()) && !saved.getAttributes().containsKey("data")),
                argThat(token -> "x-access-token".equals(token.getTokenValue())));
    }

    @Test
    void xUserInfoWithoutIdIsRejected() {
        server.expect(requestTo(X_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"errors":[{"title":"Unauthorized"}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.loadUser(request("twitter", X_USER_INFO_URI, "x-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> assertThat(ex.getError().getErrorCode())
                        .isEqualTo(CustomOAuth2UserService.INVALID_USER_INFO_RESPONSE));
        verifyNoInteractions(socialAccountService);
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
        verify(socialAccountService).recordLogin(eq(Provider.FACEBOOK),
                argThat(saved -> "1234567890".equals(saved.getName())), any(OAuth2AccessToken.class));
    }

    @Test
    void facebookUserInfoWithoutIdIsRejected() {
        // Thiếu id → DefaultOAuth2User sẽ ném IllegalArgumentException (500) nếu converter không chặn trước
        server.expect(requestTo(FACEBOOK_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"name":"Dev User"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> assertThat(ex.getError().getErrorCode())
                        .isEqualTo(CustomOAuth2UserService.INVALID_USER_INFO_RESPONSE));
        verifyNoInteractions(socialAccountService);
    }

    @Test
    void facebookUserInfoWithNonStringIdIsRejected() {
        server.expect(requestTo(FACEBOOK_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"id":{"nested":"1"},"name":"Dev User"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(socialAccountService);
    }

    @Test
    void concurrentLoginIsRetriedOnceAndSucceeds() {
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenReturn(User.builder().id(1L).displayName("Dev User").build());

        OAuth2User user = service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token"));

        assertThat(user.getName()).isEqualTo("1234567890");
        verify(socialAccountService, times(2)).recordLogin(eq(Provider.FACEBOOK), any(), any());
    }

    @Test
    void lockConflictOnConcurrentLoginIsRetriedOnce() {
        // InnoDB chọn transaction này làm nạn nhân deadlock khi nhiều lần đăng nhập đầu tiên chèn cùng unique key
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(User.builder().id(1L).displayName("Dev User").build());

        OAuth2User user = service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token"));

        assertThat(user.getName()).isEqualTo("1234567890");
        verify(socialAccountService, times(2)).recordLogin(eq(Provider.FACEBOOK), any(), any());
    }

    @Test
    void nonRetryableDatabaseFailureRejectsLoginWithoutRetry() {
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> {
                    assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR);
                    assertThat(ex.getError().getDescription()).doesNotContain("connection refused");
                });
        verify(socialAccountService, times(1)).recordLogin(any(), any(), any());
    }

    @Test
    void transactionFailureRejectsLoginWithoutRetry() {
        // DB sập / hết connection lúc mở transaction: không phải DataAccessException nhưng vẫn phải về /login?error
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> {
                    assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR);
                    assertThat(ex.getError().getDescription()).doesNotContain("EntityManager");
                });
        verify(socialAccountService, times(1)).recordLogin(any(), any(), any());
    }

    @Test
    void commitFailureRejectsLogin() {
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new TransactionSystemException("Could not commit JPA transaction"));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> assertThat(ex.getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
    }

    @Test
    void databaseFailureRejectsLoginWithGenericError() {
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenThrow(new DataIntegrityViolationException("duplicate again"));

        // Fail closed: không cho đăng nhập khi không lưu được tài khoản; thông điệp không chứa chi tiết DB
        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> {
                    assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR);
                    assertThat(ex.getError().getDescription()).doesNotContain("duplicate");
                });
        verify(socialAccountService, times(2)).recordLogin(any(), any(), any());
    }

    @Test
    void invalidProviderUserIdRejectsLogin() {
        expectFacebookUserInfo();
        when(socialAccountService.recordLogin(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid provider user id"));

        assertThatThrownBy(() -> service.loadUser(request("facebook", FACEBOOK_USER_INFO_URI, "fb-access-token")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex -> assertThat(ex.getError().getErrorCode())
                        .isEqualTo(CustomOAuth2UserService.INVALID_USER_INFO_RESPONSE));
    }

    @Test
    void unsupportedRegistrationIsRejectedBeforeCallingProvider() {
        assertThatThrownBy(() -> service.loadUser(request("github", "https://api.github.com/user", "token")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
        verifyNoInteractions(socialAccountService);
    }

    private void expectFacebookUserInfo() {
        server.expect(requestTo(FACEBOOK_USER_INFO_URI))
                .andRespond(withSuccess("""
                        {"id":"1234567890","name":"Dev User"}""", MediaType.APPLICATION_JSON));
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
