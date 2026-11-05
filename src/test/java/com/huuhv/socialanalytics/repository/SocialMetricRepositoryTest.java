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
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

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

    private Post post;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder().email("owner@example.com").displayName("Owner").build());
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
    void findBetweenReturnsSnapshotsInRangeAscending() {
        List<SocialMetric> trend = socialMetricRepository.findByPostIdAndCollectedAtBetweenOrderByCollectedAtAsc(
                post.getId(), BASE, BASE.plusHours(1));

        assertThat(trend).extracting(SocialMetric::getLikes).containsExactly(10L, 20L);
    }

    private void saveSnapshot(LocalDateTime collectedAt, long likes) {
        socialMetricRepository.save(SocialMetric.builder()
                .post(post)
                .likes(likes)
                .shares(likes / 10)
                .comments(likes / 5)
                .followers(1000)
                .collectedAt(collectedAt)
                .build());
    }
}
