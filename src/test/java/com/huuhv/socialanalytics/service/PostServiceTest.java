package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    private static final long OWNER_ID = 1L;

    @Mock
    private PostRepository postRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private PostService postService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(OWNER_ID).email("owner@example.com").displayName("Owner").build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    @Test
    void findAllQueriesOnlyCurrentUserWithDefaultSort() {
        when(postRepository.findByUserId(eq(OWNER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(post(10L))));

        PageResponse<PostResponse> result = postService.findAll(PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findByUserId(eq(OWNER_ID), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(result.content()).extracting(PostResponse::id).containsExactly(10L);
    }

    @Test
    void findAllRejectsSortOutsideWhitelist() {
        assertThatThrownBy(() -> postService.findAll(PageRequest.of(0, 20, Sort.by("user.email"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void findByIdOfOtherUserIsNotFound() {
        when(postRepository.findByIdAndUserId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createAssignsCurrentUserAsOwner() {
        when(postRepository.existsByUserIdAndProviderAndExternalId(OWNER_ID, Provider.FACEBOOK, "fb-1")).thenReturn(false);
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            post.setId(10L);
            return post;
        });

        PostResponse response = postService.create(request(Provider.FACEBOOK, "https://facebook.com/posts/1"));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(owner);
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.externalId()).isEqualTo("fb-1");
    }

    @Test
    void createRejectsUrlOutsideProviderDomain() {
        assertThatThrownBy(() -> postService.create(request(Provider.FACEBOOK, "https://evil.com/posts/1")))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicate() {
        when(postRepository.existsByUserIdAndProviderAndExternalId(OWNER_ID, Provider.FACEBOOK, "fb-1")).thenReturn(true);

        assertThatThrownBy(() -> postService.create(request(Provider.FACEBOOK, "https://facebook.com/posts/1")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void updateChangesOwnedPost() {
        Post existing = post(10L);
        when(postRepository.findByIdAndUserId(10L, OWNER_ID)).thenReturn(Optional.of(existing));
        when(postRepository.existsByUserIdAndProviderAndExternalIdAndIdNot(OWNER_ID, Provider.TWITTER, "fb-1", 10L)).thenReturn(false);
        when(postRepository.saveAndFlush(existing)).thenReturn(existing);

        PostResponse response = postService.update(10L, request(Provider.TWITTER, "https://x.com/owner/status/1"));

        assertThat(response.provider()).isEqualTo(Provider.TWITTER);
        assertThat(existing.getUrl()).isEqualTo("https://x.com/owner/status/1");
        assertThat(existing.getUser()).isSameAs(owner);
    }

    @Test
    void updateOfOtherUserIsNotFound() {
        when(postRepository.findByIdAndUserId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.update(99L, request(Provider.FACEBOOK, "https://facebook.com/posts/1")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(postRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteOfOtherUserIsNotFound() {
        when(postRepository.findByIdAndUserId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(postRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesOwnedPost() {
        Post existing = post(10L);
        when(postRepository.findByIdAndUserId(10L, OWNER_ID)).thenReturn(Optional.of(existing));

        postService.delete(10L);

        verify(postRepository).delete(existing);
    }

    @Test
    void checkOwnedPostPassesWithoutLoadingEntity() {
        when(postRepository.existsByIdAndUserId(10L, OWNER_ID)).thenReturn(true);

        postService.checkOwnedPost(10L);

        verify(postRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void checkOwnedPostOfOtherUserIsNotFound() {
        when(postRepository.existsByIdAndUserId(99L, OWNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> postService.checkOwnedPost(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Post post(Long id) {
        return Post.builder()
                .id(id)
                .user(owner)
                .provider(Provider.FACEBOOK)
                .externalId("fb-" + id)
                .url("https://facebook.com/posts/" + id)
                .build();
    }

    private static PostRequest request(Provider provider, String url) {
        return new PostRequest(provider, "fb-1", url, "content", null);
    }
}
