package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.service.SocialMetricService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * Luồng thật của job trên MySQL Testcontainers: thread pool {@code socialCrawlerExecutor} + mock client + transaction
 * ghi snapshot. Lịch chạy tự động tắt ở profile test ({@code cron: "-"}) — gọi {@link UpdateSocialMetricsJob#runOnce()}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UpdateSocialMetricsJobIntegrationTest {

    @Autowired
    private UpdateSocialMetricsJob job;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @MockitoSpyBean
    private SocialMetricService socialMetricService;

    private final Set<String> writerThreads = ConcurrentHashMap.newKeySet();

    private SocialAccount activeAccount;
    private SocialAccount expiredAccount;
    private Post fbPost1;
    private Post fbPost2;
    private Post twPostOfActiveUser;
    private Post twPostOfExpiredUser;

    @BeforeEach
    void setUp() {
        cleanUp();
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        User active = userRepository.save(User.builder().displayName("Active").build());
        activeAccount = socialAccountRepository.save(account(active, Provider.FACEBOOK, "fb-user",
                nowUtc.plusDays(30)));
        fbPost1 = postRepository.save(post(active, Provider.FACEBOOK, "fb-1"));
        fbPost2 = postRepository.save(post(active, Provider.FACEBOOK, "fb-2"));
        // User đăng nhập bằng Facebook nhưng có bài X: không có token X → không crawl được
        twPostOfActiveUser = postRepository.save(post(active, Provider.TWITTER, "tw-1"));

        User expired = userRepository.save(User.builder().displayName("Expired").build());
        expiredAccount = socialAccountRepository.save(account(expired, Provider.TWITTER, "tw-user",
                nowUtc.minusMinutes(1)));
        twPostOfExpiredUser = postRepository.save(post(expired, Provider.TWITTER, "tw-2"));

        doAnswer(invocation -> {
            writerThreads.add(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(socialMetricService).recordSnapshots(any(), anyList(), any(), any());
    }

    @AfterEach
    void cleanUp() {
        socialMetricRepository.deleteAll();
        postRepository.deleteAll();
        socialAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void crawlsAccountsInParallelPoolAndSkipsExpiredToken() {
        UpdateSocialMetricsJob.Summary summary = job.runOnce();

        assertThat(summary).isEqualTo(new UpdateSocialMetricsJob.Summary(2, 1, 1, 0, 2,
                Set.of(activeAccount.getUser().getId())));
        assertThat(writerThreads).singleElement().asString().startsWith("social-crawler-");

        List<SocialMetric> snapshots = socialMetricRepository.findAll();
        assertThat(snapshots).extracting(metric -> metric.getPost().getId())
                .containsExactlyInAnyOrder(fbPost1.getId(), fbPost2.getId());
        assertThat(snapshots).allSatisfy(metric -> assertThat(metric.getLikes()).isPositive());
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(twPostOfActiveUser.getId())).isEmpty();
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(twPostOfExpiredUser.getId())).isEmpty();

        assertThat(socialAccountRepository.findById(activeAccount.getId()).orElseThrow().getLastUpdatedAt())
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(1, ChronoUnit.MINUTES));
        assertThat(socialAccountRepository.findById(expiredAccount.getId()).orElseThrow().getLastUpdatedAt())
                .isNull();
    }

    @Test
    void postDeletedWhileCrawlingDoesNotLoseSnapshotsOfOtherPosts() {
        // User xoá 1 bài sau khi crawler đã gọi API (ngoài transaction), trước khi ghi — từ thread khác như request thật
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> postRepository.deleteById(fbPost2.getId())).join();
            return invocation.callRealMethod();
        }).when(socialMetricService).recordSnapshots(any(), anyList(), any(), any());

        UpdateSocialMetricsJob.Summary summary = job.runOnce();

        assertThat(summary).isEqualTo(new UpdateSocialMetricsJob.Summary(2, 1, 1, 0, 1,
                Set.of(activeAccount.getUser().getId())));
        assertThat(socialMetricRepository.findAll()).extracting(metric -> metric.getPost().getId())
                .containsExactly(fbPost1.getId());
        assertThat(socialAccountRepository.findById(activeAccount.getId()).orElseThrow().getLastUpdatedAt())
                .isNotNull();
    }

    @Test
    void secondRunAddsNewSnapshotsInsteadOfUpdating() {
        job.runOnce();
        job.runOnce();

        assertThat(socialMetricRepository.findAll())
                .filteredOn(metric -> metric.getPost().getId().equals(fbPost1.getId()))
                .hasSize(2);
    }

    private static SocialAccount account(User user, Provider provider, String providerUserId,
                                         LocalDateTime expiresAtUtc) {
        return SocialAccount.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .accessToken("token-" + providerUserId)
                .tokenExpiresAt(expiresAtUtc)
                .build();
    }

    private static Post post(User owner, Provider provider, String externalId) {
        String host = provider == Provider.FACEBOOK ? "https://facebook.com/posts/" : "https://x.com/i/status/";
        return Post.builder()
                .user(owner)
                .provider(provider)
                .externalId(externalId)
                .url(host + externalId)
                .build();
    }
}
