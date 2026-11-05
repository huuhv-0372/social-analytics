package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialMetricServiceTest {

    private static final long OWNER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Mock
    private SocialMetricRepository socialMetricRepository;

    @Mock
    private PostService postService;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private SocialMetricService socialMetricService;

    private final User owner = User.builder().id(OWNER_ID).email("owner@example.com").displayName("Owner").build();
    private final Post post = Post.builder().id(10L).user(owner).build();

    @Test
    void findByPostRejectsInvertedRangeBeforeQuerying() {
        assertThatThrownBy(() -> socialMetricService.findByPost(10L, NOW, NOW.minusDays(1), PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(postService, socialMetricRepository);
    }

    @Test
    void findByPostOfOtherUserIsNotFound() {
        doThrow(new ResourceNotFoundException("Post")).when(postService).checkOwnedPost(99L);

        assertThatThrownBy(() -> socialMetricService.findByPost(99L, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(socialMetricRepository);
    }

    @Test
    void findByPostUsesDefaultSortCollectedAtDesc() {
        when(socialMetricRepository.search(eq(10L), eq(null), eq(NOW), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(metric(5L))));

        var result = socialMetricService.findByPost(10L, null, NOW, PageRequest.of(0, 20));

        verify(postService).checkOwnedPost(10L);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(socialMetricRepository).search(eq(10L), eq(null), eq(NOW), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "collectedAt"));
        assertThat(result.content()).extracting(SocialMetricResponse::id).containsExactly(5L);
    }

    @Test
    void findLatestOfOtherUserIsNotFound() {
        doThrow(new ResourceNotFoundException("Post")).when(postService).checkOwnedPost(99L);

        assertThatThrownBy(() -> socialMetricService.findLatest(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(socialMetricRepository);
    }

    @Test
    void findLatestWithoutSnapshotIsNotFound() {
        when(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> socialMetricService.findLatest(10L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(postService).checkOwnedPost(10L);
    }

    @Test
    void findByIdOfOtherUserIsNotFound() {
        givenCurrentUser();
        when(socialMetricRepository.findByIdAndPostUserId(5L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> socialMetricService.findById(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSavesSnapshotForOwnedPost() {
        when(postService.getOwnedPost(10L)).thenReturn(post);
        when(socialMetricRepository.save(any(SocialMetric.class))).thenAnswer(invocation -> {
            SocialMetric metric = invocation.getArgument(0);
            metric.setId(5L);
            return metric;
        });

        SocialMetricResponse response = socialMetricService.create(new SocialMetricRequest(10L, 100L, 10L, 20L, 1000L, NOW));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.postId()).isEqualTo(10L);
        assertThat(response.likes()).isEqualTo(100L);
        assertThat(response.collectedAt()).isEqualTo(NOW);
    }

    @Test
    void createForPostOfOtherUserIsNotFound() {
        when(postService.getOwnedPost(99L)).thenThrow(new ResourceNotFoundException("Post"));

        assertThatThrownBy(() -> socialMetricService.create(new SocialMetricRequest(99L, 1L, 1L, 1L, 1L, NOW)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(socialMetricRepository, never()).save(any());
    }

    @Test
    void deleteOfOtherUserIsNotFound() {
        givenCurrentUser();
        when(socialMetricRepository.findByIdAndPostUserId(5L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> socialMetricService.delete(5L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(socialMetricRepository, never()).delete(any());
    }

    private void givenCurrentUser() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    private SocialMetric metric(Long id) {
        return SocialMetric.builder().id(id).post(post).likes(1).collectedAt(NOW).build();
    }
}
