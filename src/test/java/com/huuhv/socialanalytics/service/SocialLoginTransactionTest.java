package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.ClockConfig;
import com.huuhv.socialanalytics.config.CryptoConfig;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import com.huuhv.socialanalytics.util.AesGcmCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Luồng đăng nhập OAuth2 → lưu {@code User} + {@code SocialAccount} → dùng Service, với transaction thật trên MySQL
 * (không bọc test trong transaction): mock không phát hiện được vi phạm unique khi đăng nhập song song, hay việc
 * converter có thực sự mã hoá cột {@code access_token} không. Provider được thay bằng delegate giả (không gọi HTTP).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ClockConfig.class, CryptoConfig.class,
        SocialAccountService.class, CurrentUserService.class, PostService.class, PostUpsertService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SocialLoginTransactionTest {

    @Autowired
    private SocialAccountService socialAccountService;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private PostUpsertService postUpsertService;

    @Autowired
    private PostService postService;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        postRepository.deleteAll();
        socialAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void accessTokenIsEncryptedAtRestAndDecryptedOnRead() {
        login("facebook", "1234567890", "fb-secret-access-token");

        String stored = jdbcTemplate.queryForObject("SELECT access_token FROM social_accounts", String.class);
        assertThat(stored).isNotBlank().doesNotContain("fb-secret-access-token");
        SocialAccount account = socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "1234567890").orElseThrow();
        assertThat(account.getAccessToken()).isEqualTo("fb-secret-access-token");
        assertThat(account.getTokenExpiresAt()).isNotNull();
    }

    @Test
    void nextLoginUpdatesSameAccountInsteadOfCreatingNewOne() {
        login("twitter", "2244994945", "first-token");
        login("twitter", "2244994945", "second-token");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(socialAccountRepository.findAll()).singleElement()
                .extracting(SocialAccount::getAccessToken).isEqualTo("second-token");
    }

    @Test
    void undecryptableStoredTokenDoesNotLockUserOut() {
        // Mô phỏng đổi TOKEN_ENCRYPTION_KEY / ô dữ liệu hỏng: ciphertext cũ không giải mã được bằng key hiện tại
        OAuth2AuthenticationToken first = login("facebook", "1234567890", "old-token");
        Long userId = userIdOf(first);
        jdbcTemplate.update("UPDATE social_accounts SET access_token = ?",
                new AesGcmCipher(new byte[AesGcmCipher.KEY_LENGTH_BYTES]).encrypt("old-token"));

        // Nạp tài khoản không ném lỗi: token không đọc được coi như không có (fail closed, không gọi được API)
        assertThat(socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "1234567890").orElseThrow()
                .getAccessToken()).isNull();

        // Đăng nhập lại thành công, vẫn là user cũ, token mới ghi đè và đọc lại được
        login("facebook", "1234567890", "new-token");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(socialAccountRepository.findAll()).singleElement()
                .satisfies(account -> {
                    assertThat(account.getAccessToken()).isEqualTo("new-token");
                    assertThat(account.getUser().getId()).isEqualTo(userId);
                });
    }

    @Test
    void ownerResolvedFromPrincipalOutsideTransactionCanOwnNewPosts() {
        // Luồng import/export: getCurrentUser() gọi ngoài transaction → reference theo id lưu trong principal
        OAuth2AuthenticationToken token = login("facebook", "1234567890", "token");
        assertThat(token.getPrincipal()).isInstanceOf(AppOAuth2User.class);
        authenticate(token);

        User owner = currentUserService.getCurrentUser();
        PostUpsertService.Result result = postUpsertService.upsert(owner, List.of(postRequest()));

        assertThat(result.created()).isEqualTo(1);
        assertThat(postRepository.findAll()).singleElement()
                .satisfies(post -> assertThat(post.getUser().getId()).isEqualTo(userIdOf(token)));
    }

    @Test
    void concurrentFirstLoginsResolveToOneUserAndAccount() throws Exception {
        CustomOAuth2UserService oauth2UserService = oauth2UserService("1234567890");
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await();
            return oauth2UserService.loadUser(request("facebook", "token-" + Thread.currentThread().threadId())).getName();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(executor.submit(task), executor.submit(task));
            start.countDown();

            assertThat(results.get(0).get()).isEqualTo(results.get(1).get()).isEqualTo("1234567890");
        } finally {
            executor.shutdownNow();
        }
        assertThat(socialAccountRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void loggedInUserCanUseServicesAndOnlySeesOwnData() {
        authenticate(login("facebook", "111", "token-a"));
        postService.create(postRequest());
        PageResponse<PostResponse> ownPosts = postService.findAll(PageRequest.of(0, 20));
        assertThat(ownPosts.content()).hasSize(1);

        // User khác (tài khoản X) không thấy bài của user Facebook; trùng externalId chỉ tính trong 1 user
        authenticate(login("twitter", "222", "token-b"));
        assertThat(postService.findAll(PageRequest.of(0, 20)).content()).isEmpty();
        postService.create(postRequest());
        assertThatThrownBy(() -> postService.create(postRequest())).isInstanceOf(DuplicateResourceException.class);

        assertThat(postRepository.count()).isEqualTo(2);
        assertThat(userRepository.findAll()).extracting(User::getDisplayName).containsOnly("Dev User");
    }

    /** Đăng nhập qua {@link CustomOAuth2UserService} thật, chỉ thay bước gọi user-info-uri của provider. */
    private OAuth2AuthenticationToken login(String registrationId, String providerUserId, String accessToken) {
        OAuth2User user = oauth2UserService(providerUserId).loadUser(request(registrationId, accessToken));
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
    }

    private Long userIdOf(OAuth2AuthenticationToken token) {
        return userRepository.findBySocialAccount(
                Provider.fromRegistrationId(token.getAuthorizedClientRegistrationId()).orElseThrow(), token.getName())
                .orElseThrow().getId();
    }

    private CustomOAuth2UserService oauth2UserService(String providerUserId) {
        DefaultOAuth2UserService fakeProvider = new DefaultOAuth2UserService() {
            @Override
            public OAuth2User loadUser(OAuth2UserRequest userRequest) {
                return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                        Map.of("id", providerUserId, "name", "Dev User"), "id");
            }
        };
        return new CustomOAuth2UserService(fakeProvider, socialAccountService);
    }

    private static OAuth2UserRequest request(String registrationId, String accessToken) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .userInfoUri("https://provider.example/me")
                .userNameAttributeName("id")
                .build();
        Instant now = Instant.now();
        return new OAuth2UserRequest(registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, now, now.plusSeconds(3600)));
    }

    private static PostRequest postRequest() {
        return new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", "content", null);
    }

    private static void authenticate(OAuth2AuthenticationToken token) {
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
