package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void savesPostWithOwnerAndAuditTimestamps() {
        User owner = saveUser("owner@example.com");

        Post saved = postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(owner.getRole()).isEqualTo(Role.USER);
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
    void rejectsDuplicateProviderAndExternalId() {
        User owner = saveUser("owner@example.com");
        postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1"));

        assertThat(postRepository.existsByProviderAndExternalId(Provider.FACEBOOK, "fb-1")).isTrue();
        assertThat(postRepository.existsByProviderAndExternalId(Provider.TWITTER, "fb-1")).isFalse();
        assertThatThrownBy(() -> postRepository.saveAndFlush(newPost(owner, Provider.FACEBOOK, "fb-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsIgnoringSelfDetectsDuplicateOnlyFromOtherPosts() {
        User owner = saveUser("owner@example.com");
        Post first = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-1"));
        Post second = postRepository.save(newPost(owner, Provider.FACEBOOK, "fb-2"));

        assertThat(postRepository.existsByProviderAndExternalIdAndIdNot(Provider.FACEBOOK, "fb-1", first.getId())).isFalse();
        assertThat(postRepository.existsByProviderAndExternalIdAndIdNot(Provider.FACEBOOK, "fb-1", second.getId())).isTrue();
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
