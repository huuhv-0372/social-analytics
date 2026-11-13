package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.ClockConfig;
import com.huuhv.socialanalytics.config.CryptoConfig;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ProviderSeries;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.repository.UserImportStatisticRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chống IDOR (OWASP A01) với query thật trên MySQL: owner lấy từ {@code SecurityContext} (principal OAuth2 →
 * {@code social_accounts} → user), mọi thao tác theo id trên dữ liệu của user khác đều trả {@link ResourceNotFoundException}
 * (404 — không tiết lộ bản ghi có tồn tại) và không làm thay đổi dữ liệu. Unit test của Service chỉ kiểm tra được
 * với repository giả; ở đây kiểm tra điều kiện owner trong chính câu query.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ClockConfig.class, CryptoConfig.class, SocialAccountService.class,
        CurrentUserService.class, PostService.class, SocialMetricService.class, DashboardService.class,
        ImportStatisticsService.class, ImportHistoryService.class, ChartDataService.class,
        DashboardEventService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipIsolationTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private SocialAccountService socialAccountService;

    @Autowired
    private PostService postService;

    @Autowired
    private SocialMetricService socialMetricService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ChartDataService chartDataService;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserImportStatisticRepository userImportStatisticRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private OAuth2AuthenticationToken owner;
    private OAuth2AuthenticationToken attacker;
    private PostResponse ownersPost;
    private SocialMetricResponse ownersMetric;

    @BeforeEach
    void setUp() {
        owner = login("facebook", "111");
        attacker = login("twitter", "222");

        authenticate(owner);
        ownersPost = postService.create(new PostRequest(Provider.FACEBOOK, "fb-1",
                "https://facebook.com/posts/1", "owner's content", null));
        ownersMetric = socialMetricService.create(new SocialMetricRequest(ownersPost.id(), 10L, 2L, 3L, 100L,
                COLLECTED_AT));
        authenticate(attacker);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        socialMetricRepository.deleteAll();
        postRepository.deleteAll();
        socialAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void otherUserCannotReadPost() {
        assertNotFound(() -> postService.findById(ownersPost.id()));
        assertThat(postService.findAll(PageRequest.of(0, 20)).content()).isEmpty();
    }

    @Test
    void otherUserCannotUpdatePost() {
        assertNotFound(() -> postService.update(ownersPost.id(), new PostRequest(Provider.FACEBOOK, "fb-1",
                "https://facebook.com/posts/1", "hijacked", null)));

        authenticate(owner);
        assertThat(postService.findById(ownersPost.id()).content()).isEqualTo("owner's content");
    }

    @Test
    void otherUserCannotDeletePost() {
        assertNotFound(() -> postService.delete(ownersPost.id()));

        assertThat(postRepository.existsById(ownersPost.id())).isTrue();
    }

    @Test
    void otherUserCannotReadMetricsOfPost() {
        assertNotFound(() -> socialMetricService.findByPost(ownersPost.id(), null, null, PageRequest.of(0, 20)));
        assertNotFound(() -> socialMetricService.findLatest(ownersPost.id()));
        assertNotFound(() -> socialMetricService.findById(ownersMetric.id()));
    }

    @Test
    void otherUserCannotAddOrDeleteMetrics() {
        assertNotFound(() -> socialMetricService.create(new SocialMetricRequest(ownersPost.id(), 0L, 0L, 0L, 0L,
                COLLECTED_AT)));
        assertNotFound(() -> socialMetricService.delete(ownersMetric.id()));

        assertThat(socialMetricRepository.count()).isEqualTo(1);
    }

    @Test
    void otherUserDoesNotSeeLastUpdatedTimeOfOwner() {
        Long ownersAccountId = socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "111").orElseThrow().getId();
        socialAccountService.markMetricsUpdated(ownersAccountId, LocalDateTime.of(2026, 11, 7, 3, 0));

        assertThat(dashboardService.getDashboard().lastUpdatedAt()).isNull();

        authenticate(owner);
        assertThat(dashboardService.getDashboard().lastUpdatedAt()).isNotNull();
    }

    @Test
    void otherUserDoesNotSeeImportStatisticsOfOwner() {
        Long ownerUserId = socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "111").orElseThrow().getUser().getId();
        transactionTemplate.executeWithoutResult(status -> userImportStatisticRepository.addImport(ownerUserId,
                10, 8, 2, LocalDateTime.of(2026, 11, 7, 3, 0), LocalDateTime.of(2026, 11, 7, 3, 0, 1)));

        assertThat(dashboardService.getDashboard().importStatistics()).isNull();

        authenticate(owner);
        assertThat(dashboardService.getDashboard().importStatistics().totalRows()).isEqualTo(10);
    }

    @Test
    void otherUserDoesNotSeeChartDataOfOwner() {
        SocialAccount ownersAccount = socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "111").orElseThrow();
        socialAccountService.markMetricsUpdated(ownersAccount.getId(), LocalDateTime.of(2026, 11, 7, 3, 0));
        transactionTemplate.executeWithoutResult(status -> userImportStatisticRepository.addImport(
                ownersAccount.getUser().getId(), 10, 8, 2, LocalDateTime.of(2026, 11, 7, 3, 0),
                LocalDateTime.of(2026, 11, 7, 3, 0, 1)));

        ChartDataResponse attackersView = chartDataService.getChartData(ChartDataService.MAX_DAYS);

        // Snapshot của owner (2026-01-01) nằm trước mọi mốc ngày nhưng không được cộng cho user khác
        assertThat(attackersView.series()).isNotEmpty().allSatisfy(series -> {
            assertThat(series.likes()).hasSize(ChartDataService.MAX_DAYS).containsOnlyNulls();
            assertThat(series.shares()).containsOnlyNulls();
            assertThat(series.comments()).containsOnlyNulls();
            assertThat(series.followers()).containsOnlyNulls();
        });
        assertThat(attackersView.lastUpdatedAt()).isNull();
        assertThat(attackersView.importStatistics()).isNull();

        authenticate(owner);
        ChartDataResponse ownersView = chartDataService.getChartData(1);
        assertThat(ownersView.series())
                .filteredOn(series -> series.provider() == Provider.FACEBOOK)
                .singleElement()
                .extracting(ProviderSeries::likes)
                .isEqualTo(List.of(10L));
        assertThat(ownersView.lastUpdatedAt()).isNotNull();
        assertThat(ownersView.importStatistics().totalRows()).isEqualTo(10);
    }

    @Test
    void ownerStillHasFullAccess() {
        authenticate(owner);

        assertThat(postService.findById(ownersPost.id()).externalId()).isEqualTo("fb-1");
        assertThat(socialMetricService.findLatest(ownersPost.id()).id()).isEqualTo(ownersMetric.id());
        socialMetricService.delete(ownersMetric.id());
        postService.delete(ownersPost.id());
        assertThat(postRepository.count()).isZero();
    }

    private static void assertNotFound(Executable action) {
        assertThatThrownBy(action::execute).isInstanceOf(ResourceNotFoundException.class);
    }

    private OAuth2AuthenticationToken login(String registrationId, String providerUserId) {
        OAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", providerUserId, "name", "User " + providerUserId), "id");
        Instant now = Instant.now();
        socialAccountService.recordLogin(Provider.fromRegistrationId(registrationId).orElseThrow(), user,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token-" + providerUserId,
                        now, now.plusSeconds(3600)));
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
    }

    private static void authenticate(OAuth2AuthenticationToken token) {
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
