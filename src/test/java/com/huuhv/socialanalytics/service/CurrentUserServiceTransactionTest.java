package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chạy Service với transaction thật trên MySQL (không bọc test trong transaction): unit test dùng mock
 * không phát hiện được vi phạm unique khi tạo user song song hay lỗi khi Service đọc user chưa được tạo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CurrentUserService.class, UserProvisioningService.class, PostService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CurrentUserServiceTransactionTest {

    @Autowired
    private PostService postService;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void provisionedUserCanUseReadOnlyService() {
        authenticateAs("first@example.com");
        currentUserService.provisionCurrentUserIfMissing();

        PageResponse<PostResponse> page = postService.findAll(PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        assertThat(userRepository.findByEmail("first@example.com")).isPresent();
    }

    @Test
    void concurrentFirstRequestsResolveToSameUser() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> task = () -> {
            authenticateAs("race@example.com");
            try {
                start.await();
                currentUserService.provisionCurrentUserIfMissing();
                return currentUserService.getCurrentUser().getId();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> results = List.of(executor.submit(task), executor.submit(task));
            start.countDown();

            assertThat(results.get(0).get()).isNotNull().isEqualTo(results.get(1).get());
        } finally {
            executor.shutdownNow();
        }
        assertThat(userRepository.findAll()).extracting(User::getEmail).containsExactly("race@example.com");
    }

    @Test
    void notProvisionedUserIsRejected() {
        authenticateAs("ghost@example.com");

        assertThatThrownBy(() -> postService.findAll(PageRequest.of(0, 20)))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThat(userRepository.findByEmail("ghost@example.com")).isEmpty();
    }

    @Test
    void sameExternalIdIsDuplicateOnlyWithinOneUser() {
        authenticateAs("a@example.com");
        currentUserService.provisionCurrentUserIfMissing();
        postService.create(request());

        authenticateAs("b@example.com");
        currentUserService.provisionCurrentUserIfMissing();
        postService.create(request());
        assertThatThrownBy(() -> postService.create(request()))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(postRepository.count()).isEqualTo(2);
    }

    private static PostRequest request() {
        return new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", "content", null);
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.NO_AUTHORITIES));
    }
}
