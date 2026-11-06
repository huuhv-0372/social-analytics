package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
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

    private SocialMetric saveSnapshot(LocalDateTime collectedAt, long likes) {
        return socialMetricRepository.save(SocialMetric.builder()
                .post(post)
                .likes(likes)
                .shares(likes / 10)
                .comments(likes / 5)
                .followers(1000)
                .collectedAt(collectedAt)
                .build());
    }
}
