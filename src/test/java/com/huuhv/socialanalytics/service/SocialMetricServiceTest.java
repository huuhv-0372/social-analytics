package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.service.SocialMetricService.SnapshotImport;
import com.huuhv.socialanalytics.service.SocialMetricService.SnapshotKey;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class SocialMetricServiceTest {

    private static final long OWNER_ID = 1L;
    /** Cùng 1 thời điểm: dạng API ({@link Instant}) và dạng lưu DB (giờ UTC). */
    private static final Instant NOW_INSTANT = Instant.parse("2026-01-01T12:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Mock
    private SocialMetricRepository socialMetricRepository;

    @Mock
    private PostService postService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private DashboardEventService dashboardEventService;

    @InjectMocks
    private SocialMetricService socialMetricService;

    private final User owner = User.builder().id(OWNER_ID).email("owner@example.com").displayName("Owner").build();
    private final Post post = Post.builder().id(10L).user(owner).build();

    @Test
    void findByPostRejectsInvertedRangeBeforeQuerying() {
        assertThatThrownBy(() -> socialMetricService.findByPost(10L, NOW_INSTANT, NOW_INSTANT.minus(Duration.ofDays(1)),
                PageRequest.of(0, 20)))
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

        // Mốc Instant được đổi sang giờ UTC trước khi so với cột collected_at
        var result = socialMetricService.findByPost(10L, null, NOW_INSTANT, PageRequest.of(0, 20));

        verify(postService).checkOwnedPost(10L);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(socialMetricRepository).search(eq(10L), eq(null), eq(NOW), captor.capture());
        assertThat(captor.getValue().getSort()).containsExactly(Sort.Order.desc("collectedAt"), Sort.Order.desc("id"));
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
    void findLatestReturnsNewestSnapshot() {
        when(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(10L)).thenReturn(Optional.of(metric(7L)));

        SocialMetricResponse response = socialMetricService.findLatest(10L);

        verify(postService).checkOwnedPost(10L);
        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.collectedAt()).isEqualTo(NOW_INSTANT);
    }

    @Test
    void findByIdReturnsMetricOfOwnedPost() {
        givenCurrentUser();
        when(socialMetricRepository.findByIdAndPostUserId(5L, OWNER_ID)).thenReturn(Optional.of(metric(5L)));

        SocialMetricResponse response = socialMetricService.findById(5L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.postId()).isEqualTo(10L);
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

        SocialMetricResponse response = socialMetricService.create(
                new SocialMetricRequest(10L, 100L, 10L, 20L, 1000L, NOW_INSTANT));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.postId()).isEqualTo(10L);
        assertThat(response.likes()).isEqualTo(100L);
        assertThat(response.collectedAt()).isEqualTo(NOW_INSTANT);
        // Như job crawl / import Excel: dashboard của owner được báo làm mới
        verify(dashboardEventService).metricsUpdated(Set.of(OWNER_ID));
    }

    @Test
    void createStoresCollectedAtAsUtc() {
        when(postService.getOwnedPost(10L)).thenReturn(post);
        when(socialMetricRepository.save(any(SocialMetric.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Client ở +07:00 gửi 19:00 giờ địa phương = 12:00 UTC
        Instant fromVietnam = OffsetDateTime.parse("2026-01-01T19:00:00+07:00").toInstant();

        socialMetricService.create(new SocialMetricRequest(10L, 1L, 1L, 1L, 1L, fromVietnam));

        ArgumentCaptor<SocialMetric> captor = ArgumentCaptor.forClass(SocialMetric.class);
        verify(socialMetricRepository).save(captor.capture());
        assertThat(captor.getValue().getCollectedAt()).isEqualTo(NOW);
    }

    /** Mọi đường ghi cắt collectedAt về mili giây — unique (post_id, collected_at) so trên cùng độ chính xác. */
    @Test
    void createTruncatesCollectedAtToMillisAndChecksDuplicateOnThatValue() {
        when(postService.getOwnedPost(10L)).thenReturn(post);
        when(socialMetricRepository.save(any(SocialMetric.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime millis = LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123_000_000);

        socialMetricService.create(new SocialMetricRequest(10L, 1L, 1L, 1L, 1L,
                Instant.parse("2026-01-01T12:00:00.123456Z")));

        verify(socialMetricRepository).existsByPostIdAndCollectedAt(10L, millis);
        ArgumentCaptor<SocialMetric> captor = ArgumentCaptor.forClass(SocialMetric.class);
        verify(socialMetricRepository).save(captor.capture());
        assertThat(captor.getValue().getCollectedAt()).isEqualTo(millis);
    }

    @Test
    void createRejectsSnapshotAlreadyRecordedAtSameTime() {
        when(postService.getOwnedPost(10L)).thenReturn(post);
        when(socialMetricRepository.existsByPostIdAndCollectedAt(10L, NOW)).thenReturn(true);

        assertThatThrownBy(() -> socialMetricService.create(new SocialMetricRequest(10L, 1L, 1L, 1L, 1L, NOW_INSTANT)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(socialMetricRepository, never()).save(any());
        verifyNoInteractions(dashboardEventService);
    }

    @Test
    void toSnapshotTimeIsUtcTruncatedToMillis() {
        assertThat(SocialMetricService.toSnapshotTime(Instant.parse("2026-01-01T19:00:00.999999999+07:00")))
                .isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0, 0, 999_000_000));
        assertThat(SocialMetricService.toSnapshotTime(null)).isNull();
    }

    @Test
    void createForPostOfOtherUserIsNotFound() {
        when(postService.getOwnedPost(99L)).thenThrow(new ResourceNotFoundException("Post"));

        assertThatThrownBy(() -> socialMetricService.create(new SocialMetricRequest(99L, 1L, 1L, 1L, 1L, NOW_INSTANT)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(socialMetricRepository, never()).save(any());
        verifyNoInteractions(dashboardEventService);
    }

    @Test
    void deleteOfOtherUserIsNotFound() {
        givenCurrentUser();
        when(socialMetricRepository.findByIdAndPostUserId(5L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> socialMetricService.delete(5L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(socialMetricRepository, never()).delete(any());
        verifyNoInteractions(dashboardEventService);
    }

    @Test
    void deleteRemovesMetricOfOwnedPost() {
        givenCurrentUser();
        SocialMetric existing = metric(5L);
        when(socialMetricRepository.findByIdAndPostUserId(5L, OWNER_ID)).thenReturn(Optional.of(existing));

        socialMetricService.delete(5L);

        verify(socialMetricRepository).delete(existing);
        verify(dashboardEventService).metricsUpdated(Set.of(OWNER_ID));
    }

    /**
     * Snapshot trùng = cùng bài + cùng collectedAt tới mili giây (độ chính xác ô Excel): khoảng [t, t + 1ms) giờ UTC,
     * 1 query cho cả danh sách, vị trí trả về đổi từ 1-based (JSON_TABLE) sang 0-based.
     */
    @Test
    void findExistingSnapshotIndexesQueriesMillisecondWindowsInUtcOnce() {
        when(socialMetricRepository.findExistingSnapshotPositions(anyString())).thenReturn(List.of(2L));

        Set<Integer> existing = socialMetricService.findExistingSnapshotIndexes(List.of(
                new SnapshotKey(10L, NOW_INSTANT),
                new SnapshotKey(11L, Instant.parse("2026-01-01T19:00:00.123456789+07:00"))));

        assertThat(existing).containsExactly(1);
        verify(socialMetricRepository).findExistingSnapshotPositions(
                "[{\"postId\":10,\"from\":\"2026-01-01 12:00:00.000000\",\"to\":\"2026-01-01 12:00:00.001000\"},"
                        + "{\"postId\":11,\"from\":\"2026-01-01 12:00:00.123000\",\"to\":\"2026-01-01 12:00:00.124000\"}]");
    }

    @Test
    void findExistingSnapshotIndexesOfNothingDoesNotQuery() {
        assertThat(socialMetricService.findExistingSnapshotIndexes(List.of())).isEmpty();
        verifyNoInteractions(socialMetricRepository);
    }

    @Test
    void recordSnapshotsInsertsOneSnapshotPerPostAndMarksAccountUpdated() {
        Post other = Post.builder().id(11L).build();
        when(postService.lockExistingIds(List.of(10L, 11L))).thenReturn(Set.of(10L, 11L));
        when(postService.getReference(10L)).thenReturn(post);
        when(postService.getReference(11L)).thenReturn(other);
        when(socialMetricRepository.insertAll(anyList())).thenReturn(2);
        LocalDateTime updatedAtUtc = NOW.minusHours(7);

        int saved = socialMetricService.recordSnapshots(7L, List.of(
                new CrawledMetric(10L, 5, 1, 2, 300),
                new CrawledMetric(11L, 8, 0, 0, 40)), NOW, updatedAtUtc);

        assertThat(saved).isEqualTo(2);
        ArgumentCaptor<List<SocialMetric>> inserted = ArgumentCaptor.captor();
        verify(socialMetricRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue())
                .extracting(SocialMetric::getPost, SocialMetric::getLikes, SocialMetric::getShares,
                        SocialMetric::getComments, SocialMetric::getFollowers, SocialMetric::getCollectedAt)
                .containsExactly(
                        tuple(post, 5L, 1L, 2L, 300L, NOW),
                        tuple(other, 8L, 0L, 0L, 40L, NOW));
        // Không dùng saveAll: id IDENTITY → 1 câu INSERT cho mỗi snapshot
        verify(socialMetricRepository, never()).saveAll(anyList());
        verify(socialAccountService).markMetricsUpdated(7L, updatedAtUtc);
        // Job chạy nền: không dựa vào user đăng nhập
        verifyNoInteractions(currentUserService);
    }

    @Test
    void recordSnapshotsTruncatesCollectedAtToMillis() {
        when(postService.lockExistingIds(List.of(10L))).thenReturn(Set.of(10L));
        when(postService.getReference(10L)).thenReturn(post);
        when(socialMetricRepository.insertAll(anyList())).thenReturn(1);
        LocalDateTime micros = LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123_456_000);

        socialMetricService.recordSnapshots(7L, List.of(new CrawledMetric(10L, 5, 1, 2, 300)), micros, micros);

        ArgumentCaptor<List<SocialMetric>> inserted = ArgumentCaptor.captor();
        verify(socialMetricRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue()).extracting(SocialMetric::getCollectedAt)
                .containsExactly(LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123_000_000));
        // Mốc last_updated_at không phải snapshot — giữ nguyên độ chính xác
        verify(socialAccountService).markMetricsUpdated(7L, micros);
    }

    @Test
    void recordSnapshotsSkipsPostsDeletedWhileCrawlingAndKeepsOthers() {
        when(postService.lockExistingIds(List.of(10L, 11L))).thenReturn(Set.of(10L));
        when(postService.getReference(10L)).thenReturn(post);
        when(socialMetricRepository.insertAll(anyList())).thenReturn(1);

        int saved = socialMetricService.recordSnapshots(7L, List.of(
                new CrawledMetric(10L, 5, 1, 2, 300),
                new CrawledMetric(11L, 8, 0, 0, 40)), NOW, NOW);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<List<SocialMetric>> inserted = ArgumentCaptor.captor();
        verify(socialMetricRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue()).extracting(SocialMetric::getPost).containsExactly(post);
        verify(postService, never()).getReference(11L);
        verify(socialAccountService).markMetricsUpdated(7L, NOW);
    }

    @Test
    void recordSnapshotsWithoutMetricsStillMarksAccountUpdated() {
        assertThat(socialMetricService.recordSnapshots(7L, List.of(), NOW, NOW)).isZero();

        verify(socialAccountService).markMetricsUpdated(7L, NOW);
        // Không có gì để ghi → không khoá bài, không INSERT rỗng
        verifyNoInteractions(postService, socialMetricRepository);
    }

    @Test
    void importSnapshotsInsertsInUtcSkipsDeletedPostsAndNotifiesOwner() {
        // 10 lặp 2 lần: chỉ khoá mỗi bài 1 lần
        when(postService.lockExistingIds(List.of(10L, 11L))).thenReturn(Set.of(10L));
        when(postService.getReference(10L)).thenReturn(post);
        when(socialMetricRepository.insertAll(anyList())).thenReturn(2);

        SnapshotImport result = socialMetricService.importSnapshots(OWNER_ID, List.of(
                new SocialMetricRequest(10L, 5L, 1L, 2L, 300L, Instant.parse("2026-01-01T02:00:00.1234567Z")),
                new SocialMetricRequest(11L, 8L, 0L, 0L, 40L, NOW_INSTANT),
                new SocialMetricRequest(10L, 6L, 1L, 2L, 301L, NOW_INSTANT)));

        // Bài 11 bị xoá giữa lúc tra và lúc ghi → trả về để Service import báo lỗi đúng dòng
        assertThat(result).isEqualTo(new SnapshotImport(2, Set.of(11L)));
        ArgumentCaptor<List<SocialMetric>> inserted = ArgumentCaptor.captor();
        verify(socialMetricRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue())
                .extracting(SocialMetric::getPost, SocialMetric::getLikes, SocialMetric::getFollowers,
                        SocialMetric::getCollectedAt)
                .containsExactly(
                        // Lưu giờ UTC, cắt về mili giây (độ chính xác ô Excel, unique (post_id, collected_at))
                        tuple(post, 5L, 300L, LocalDateTime.of(2026, 1, 1, 2, 0, 0, 123_000_000)),
                        tuple(post, 6L, 301L, NOW));
        verify(postService, never()).getReference(11L);
        // Dashboard của owner làm mới (gửi sau commit); không đụng mốc last_updated_at của job crawl
        verify(dashboardEventService).metricsUpdated(Set.of(OWNER_ID));
        verifyNoInteractions(socialAccountService);
    }

    @Test
    void importSnapshotsWithNothingSavedDoesNotNotify() {
        when(postService.lockExistingIds(List.of(10L))).thenReturn(Set.of());
        when(socialMetricRepository.insertAll(List.of())).thenReturn(0);

        assertThat(socialMetricService.importSnapshots(OWNER_ID,
                List.of(new SocialMetricRequest(10L, 1L, 1L, 1L, 1L, NOW_INSTANT))))
                .isEqualTo(new SnapshotImport(0, Set.of(10L)));
        assertThat(socialMetricService.importSnapshots(OWNER_ID, List.of()))
                .isEqualTo(new SnapshotImport(0, Set.of()));

        verifyNoInteractions(dashboardEventService);
    }

    private void givenCurrentUser() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    private SocialMetric metric(Long id) {
        return SocialMetric.builder().id(id).post(post).likes(1).collectedAt(NOW).build();
    }
}
