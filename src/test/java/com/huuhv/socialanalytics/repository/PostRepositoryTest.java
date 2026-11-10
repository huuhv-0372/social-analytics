package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.response.PostRef;
import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// Chạy trên MySQL riêng của Testcontainers (migration viết cho MySQL, cần Docker); mỗi test tự rollback
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PostRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Test
    void reportRowsHaveLatestSnapshotPerOwnPost() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        Post withMetrics = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        postRepository.save(newPost(owner, Provider.TWITTER, "tw-1"));
        Post otherPost = postRepository.save(newPost(other, Provider.FACEBOOK, "fb-9"));
        saveMetric(withMetrics, 10, base);
        saveMetric(withMetrics, 30, base.plusHours(2));
        saveMetric(withMetrics, 20, base.plusHours(1));
        saveMetric(otherPost, 99, base.plusHours(5));

        List<PostReportRow> rows = postRepository.findReportRows(owner.getId(), Limit.unlimited());

        // Chỉ bài của owner, sắp theo provider rồi externalId; bài chưa có snapshot vẫn có dòng với metric null
        assertThat(rows).extracting(PostReportRow::externalId, PostReportRow::likes, PostReportRow::metricsCollectedAt)
                .containsExactly(
                        tuple("fb-1", 30L, base.plusHours(2)),
                        tuple("tw-1", null, null));
    }

    @Test
    void reportHasOneRowWhenSnapshotsShareCollectedAt() {
        User owner = saveUser("owner@example.com");
        Post post = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        LocalDateTime at = LocalDateTime.of(2026, 1, 1, 0, 0);
        saveMetric(post, 1, at);
        saveMetric(post, 2, at);

        List<PostReportRow> rows = postRepository.findReportRows(owner.getId(), Limit.unlimited());

        assertThat(rows).singleElement().extracting(PostReportRow::likes).isEqualTo(2L);
    }

    @Test
    void reportRowsRespectLimit() {
        User owner = saveUser("owner@example.com");
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-2"));
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-3"));

        assertThat(postRepository.findReportRows(owner.getId(), Limit.of(2)))
                .extracting(PostReportRow::externalId).containsExactly("fb-1", "fb-2");
    }

    @Test
    void savesPostWithOwnerAndAuditTimestamps() {
        User owner = saveUser("owner@example.com");

        Post saved = postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThat(saved.getId()).isNotNull();
        // created_at / updated_at lưu giờ UTC, không theo múi giờ JVM
        assertThat(Duration.between(saved.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).abs())
                .isLessThan(Duration.ofMinutes(1));
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(owner.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void updateRefreshesUpdatedAtButKeepsCreatedAt() {
        Post saved = postRepository.saveAndFlush(newPost(saveUser("owner@example.com"), Provider.FACEBOOK, "fb-1"));
        LocalDateTime createdAt = saved.getCreatedAt();

        saved.setContent("changed");
        Post updated = postRepository.saveAndFlush(saved);

        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }

    @Test
    void findByIdAndUserIdReturnsEmptyForOtherUser() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        Post post = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThat(postRepository.findByIdAndUserId(post.getId(), owner.getId())).isPresent();
        assertThat(postRepository.findByIdAndUserId(post.getId(), other.getId())).isEmpty();
    }

    @Test
    void findByUserIdReturnsOnlyOwnPosts() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        postRepository.save(newPost(owner, Provider.TWITTER, "tw-1"));
        postRepository.save(newPost(other, Provider.FACEBOOK, "fb-2"));

        Page<Post> page = postRepository.findByUserId(owner.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(p -> p.getUser().getId().equals(owner.getId()));
    }

    @Test
    void sameExternalIdIsAllowedForDifferentUsers() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1"));

        postRepository.saveAndFlush(newPost(other, Provider.FACEBOOK, "fb-1"));

        assertThat(postRepository.existsByUserIdAndProviderAndExternalId(owner.getId(), Provider.FACEBOOK, "fb-1")).isTrue();
        assertThat(postRepository.existsByUserIdAndProviderAndExternalId(owner.getId(), Provider.TWITTER, "fb-1")).isFalse();
    }

    @Test
    void rejectsDuplicateProviderAndExternalIdOfSameUser() {
        User owner = saveUser("owner@example.com");
        postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThatThrownBy(() -> postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByIdAndUserIdChecksOwnership() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        Post post = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThat(postRepository.existsByIdAndUserId(post.getId(), owner.getId())).isTrue();
        assertThat(postRepository.existsByIdAndUserId(post.getId(), other.getId())).isFalse();
    }

    @Test
    void existsIgnoringSelfDetectsDuplicateOnlyFromOtherPosts() {
        User owner = saveUser("owner@example.com");
        Post first = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        Post second = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-2"));

        Long ownerId = owner.getId();
        assertThat(postRepository.existsByUserIdAndProviderAndExternalIdAndIdNot(ownerId, Provider.FACEBOOK, "fb-1", first.getId())).isFalse();
        assertThat(postRepository.existsByUserIdAndProviderAndExternalIdAndIdNot(ownerId, Provider.FACEBOOK, "fb-1", second.getId())).isTrue();
    }

    @Test
    void findByExternalIdInReturnsOnlyOwnPostsOfProvider() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-2"));
        postRepository.save(newPost(owner, Provider.TWITTER, "fb-3"));
        postRepository.save(newPost(other, Provider.FACEBOOK, "fb-3"));

        List<Post> found = postRepository.findByUserIdAndProviderAndExternalIdIn(
                owner.getId(), Provider.FACEBOOK, List.of("fb-1", "fb-3", "missing"));

        assertThat(found).extracting(Post::getExternalId).containsExactly("fb-1");
    }

    @Test
    void postRefsContainOnlyPostsOfUserOnProvider() {
        User owner = saveUser("owner@example.com");
        User other = saveUser("other@example.com");
        Post first = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        Post second = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-2"));
        postRepository.save(newPost(owner, Provider.TWITTER, "tw-1"));
        postRepository.save(newPost(other, Provider.FACEBOOK, "fb-3"));

        assertThat(postRepository.findRefsByUserIdAndProvider(owner.getId(), Provider.FACEBOOK))
                .containsExactly(new PostRef(first.getId(), "fb-1"), new PostRef(second.getId(), "fb-2"));
        assertThat(postRepository.findRefsByUserIdAndProvider(other.getId(), Provider.TWITTER)).isEmpty();
    }

    private void saveMetric(Post post, long likes, LocalDateTime collectedAt) {
        socialMetricRepository.saveAndFlush(SocialMetric.builder()
                .post(post).likes(likes).shares(1).comments(2).followers(3).collectedAt(collectedAt).build());
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder().email(email).displayName("Test User").build());
    }

    private static Post newPost(User owner, Provider provider, String externalId) {
        return Post.builder()
                .user(owner)
                .provider(provider)
                .externalId(externalId)
                .url("https://facebook.com/posts/" + externalId)
                .content("Nội dung " + externalId)
                .build();
    }
}
