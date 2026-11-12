package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.response.ProviderMetricTotals;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SocialMetricRepositoryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private Post post;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().email("owner@example.com").displayName("Owner").build());
        post = postRepository.save(Post.builder()
                .user(owner)
                .provider(Provider.TWITTER)
                .externalId("tw-1")
                .url("https://x.com/owner/status/1")
                .build());
        // Mỗi lần crawl là 1 snapshot mới, lưu không theo thứ tự thời gian
        saveSnapshot(BASE.plusHours(2), 30);
        saveSnapshot(BASE, 10);
        saveSnapshot(BASE.plusHours(1), 20);
    }

    @Test
    void findTopReturnsLatestSnapshot() {
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(post.getId()))
                .get()
                .extracting(SocialMetric::getLikes)
                .isEqualTo(30L);
    }

    @Test
    void findTopReturnsEmptyWhenNoSnapshot() {
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(-1L)).isEmpty();
    }

    @Test
    void searchWithoutRangeReturnsAllSnapshotsPaged() {
        Page<SocialMetric> page = socialMetricRepository.search(post.getId(), null, null,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "collectedAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(SocialMetric::getLikes).containsExactly(30L, 20L);
    }

    @Test
    void searchAppliesOptionalBounds() {
        Sort ascending = Sort.by("collectedAt");

        assertThat(socialMetricRepository.search(post.getId(), BASE.plusHours(1), null, PageRequest.of(0, 10, ascending)))
                .extracting(SocialMetric::getLikes).containsExactly(20L, 30L);
        assertThat(socialMetricRepository.search(post.getId(), null, BASE.plusHours(1), PageRequest.of(0, 10, ascending)))
                .extracting(SocialMetric::getLikes).containsExactly(10L, 20L);
    }

    @Test
    void findByIdAndPostUserIdReturnsEmptyForOtherUser() {
        SocialMetric metric = saveSnapshot(BASE.plusHours(3), 40);
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());

        assertThat(socialMetricRepository.findByIdAndPostUserId(metric.getId(), owner.getId())).isPresent();
        assertThat(socialMetricRepository.findByIdAndPostUserId(metric.getId(), other.getId())).isEmpty();
    }

    @Test
    void insertAllWritesEverySnapshotAcrossMultipleStatements() {
        Post second = postRepository.save(Post.builder()
                .user(owner)
                .provider(Provider.TWITTER)
                .externalId("tw-2")
                .url("https://x.com/owner/status/2")
                .build());
        LocalDateTime collectedAt = LocalDateTime.of(2026, 11, 10, 3, 0, 0, 123_456_000);
        // Nhiều hơn 1 câu INSERT (ROWS_PER_STATEMENT dòng/câu) để kiểm tra cả phần chia lô
        int count = SocialMetricRepositoryImpl.ROWS_PER_STATEMENT + 1;
        List<SocialMetric> snapshots = IntStream.range(0, count)
                .mapToObj(i -> SocialMetric.builder()
                        .post(i % 2 == 0 ? post : second)
                        .likes(i)
                        .shares(i + 1)
                        .comments(i + 2)
                        .followers(i + 3)
                        .collectedAt(collectedAt)
                        .build())
                .toList();

        assertThat(socialMetricRepository.insertAll(snapshots)).isEqualTo(count);
        entityManager.clear();

        assertThat(socialMetricRepository.search(second.getId(), null, null, PageRequest.of(0, 1000)))
                .hasSize(count / 2)
                .allSatisfy(metric -> {
                    assertThat(metric.getShares()).isEqualTo(metric.getLikes() + 1);
                    assertThat(metric.getComments()).isEqualTo(metric.getLikes() + 2);
                    assertThat(metric.getFollowers()).isEqualTo(metric.getLikes() + 3);
                    assertThat(metric.getCollectedAt()).isEqualTo(collectedAt);
                });
        // 3 snapshot của setUp + phần mới
        assertThat(socialMetricRepository.search(post.getId(), null, null, PageRequest.of(0, 1000)))
                .hasSize(3 + count - count / 2);
    }

    @Test
    void insertAllOfNothingWritesNothing() {
        assertThat(socialMetricRepository.insertAll(List.of())).isZero();
        assertThat(socialMetricRepository.count()).isEqualTo(3);
    }

    @Test
    void sumLatestByProviderAddsLatestSnapshotOfEachPostBeforeCutoff() {
        Post secondTweet = savePost(owner, Provider.TWITTER, "tw-2");
        Post facebookPost = savePost(owner, Provider.FACEBOOK, "fb-1");
        saveSnapshot(secondTweet, BASE.plusMinutes(90), 100);
        saveSnapshot(facebookPost, BASE.plusMinutes(30), 7);

        // Snapshot đúng bằng mốc (BASE + 1h, likes 20) không được tính; tw-2 chưa có snapshot trước mốc
        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE.plusHours(1)))
                .containsExactlyInAnyOrder(
                        new ProviderMetricTotals(Provider.TWITTER, 10L, 1L, 2L, 1000L),
                        new ProviderMetricTotals(Provider.FACEBOOK, 7L, 0L, 1L, 1000L));
        // fb-1 không có snapshot mới → giữ giá trị cũ (carry-forward); 2 tweet cộng likes/shares/comments,
        // followers của cùng tác giả không cộng lặp
        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE.plusHours(3)))
                .containsExactlyInAnyOrder(
                        new ProviderMetricTotals(Provider.TWITTER, 130L, 13L, 26L, 1000L),
                        new ProviderMetricTotals(Provider.FACEBOOK, 7L, 0L, 1L, 1000L));
    }

    @Test
    void sumLatestByProviderTakesLargestFollowersInsteadOfAddingThemPerPost() {
        Post secondTweet = savePost(owner, Provider.TWITTER, "tw-2");
        // Snapshot mới nhất của tw-1 (BASE + 2h) có 1000 followers; tw-2 được crawl sau, tác giả đã có 1200
        saveSnapshot(secondTweet, BASE.plusMinutes(150), 100, 1200);

        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE.plusHours(3)))
                .singleElement()
                .extracting(ProviderMetricTotals::likes, ProviderMetricTotals::followers)
                .containsExactly(130L, 1200L);
    }

    @Test
    void sumLatestByProviderIsEmptyBeforeFirstSnapshot() {
        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE)).isEmpty();
    }

    @Test
    void sumLatestByProviderExcludesPostsOfOtherUsers() {
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());
        saveSnapshot(savePost(other, Provider.TWITTER, "tw-1"), BASE, 5000);

        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE.plusHours(3)))
                .containsExactly(new ProviderMetricTotals(Provider.TWITTER, 30L, 3L, 6L, 1000L));
        assertThat(socialMetricRepository.sumLatestByProvider(other.getId(), BASE.plusHours(3)))
                .containsExactly(new ProviderMetricTotals(Provider.TWITTER, 5000L, 500L, 1000L, 1000L));
    }

    @Test
    void sumLatestByProviderCountsOneSnapshotPerPostWhenCollectedAtTies() {
        // Cùng collectedAt với snapshot 30 likes → lấy bản id lớn hơn, không cộng cả 2
        saveSnapshot(BASE.plusHours(2), 35);

        assertThat(socialMetricRepository.sumLatestByProvider(owner.getId(), BASE.plusHours(3)))
                .extracting(ProviderMetricTotals::likes)
                .containsExactly(35L);
    }

    private Post savePost(User user, Provider provider, String externalId) {
        String url = provider == Provider.FACEBOOK
                ? "https://facebook.com/posts/" + externalId
                : "https://x.com/owner/status/" + externalId;
        return postRepository.save(Post.builder()
                .user(user)
                .provider(provider)
                .externalId(externalId)
                .url(url)
                .build());
    }

    private SocialMetric saveSnapshot(LocalDateTime collectedAt, long likes) {
        return saveSnapshot(post, collectedAt, likes);
    }

    private SocialMetric saveSnapshot(Post target, LocalDateTime collectedAt, long likes) {
        return saveSnapshot(target, collectedAt, likes, 1000);
    }

    private SocialMetric saveSnapshot(Post target, LocalDateTime collectedAt, long likes, long followers) {
        return socialMetricRepository.save(SocialMetric.builder()
                .post(target)
                .likes(likes)
                .shares(likes / 10)
                .comments(likes / 5)
                .followers(followers)
                .collectedAt(collectedAt)
                .build());
    }
}
