package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Snapshot metric của bài viết. Snapshot là bất biến nên không có thao tác cập nhật.
 * Quyền sở hữu bài viết kiểm tra qua {@link PostService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMetricService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("collectedAt", "likes", "shares", "comments", "followers");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "collectedAt");
    private static final String RESOURCE = "Metric";

    private final SocialMetricRepository socialMetricRepository;
    private final PostService postService;
    private final CurrentUserService currentUserService;
    private final SocialAccountService socialAccountService;

    @Transactional(readOnly = true)
    public PageResponse<SocialMetricResponse> findByPost(Long postId, LocalDateTime from, LocalDateTime to,
                                                         Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        postService.checkOwnedPost(postId);
        Pageable safePageable = PageableUtils.withAllowedSort(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return PageResponse.from(socialMetricRepository.search(postId, from, to, safePageable)
                .map(SocialMetricResponse::from));
    }

    @Transactional(readOnly = true)
    public SocialMetricResponse findLatest(Long postId) {
        postService.checkOwnedPost(postId);
        return socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(postId)
                .map(SocialMetricResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    @Transactional(readOnly = true)
    public SocialMetricResponse findById(Long id) {
        return SocialMetricResponse.from(findOwnedMetric(id));
    }

    @Transactional
    public SocialMetricResponse create(SocialMetricRequest request) {
        Post post = postService.getOwnedPost(request.postId());
        SocialMetric saved = socialMetricRepository.save(SocialMetric.builder()
                .post(post)
                .likes(request.likes())
                .shares(request.shares())
                .comments(request.comments())
                .followers(request.followers())
                .collectedAt(request.collectedAt())
                .build());
        return SocialMetricResponse.from(saved);
    }

    /**
     * Lưu kết quả crawl của 1 tài khoản: snapshot mới cho từng bài + {@code last_updated_at} của tài khoản trong
     * <b>cùng 1 transaction</b> — lỗi giữa chừng thì không lưu gì, lần chạy sau làm lại (không có trạng thái nửa vời).
     * Bài bị xoá trong lúc crawl làm vi phạm khoá ngoại → cả lô rollback, lần sau bài đó không còn trong danh sách.
     *
     * @param metrics      số liệu đã được validate (bài thuộc tài khoản, giá trị không âm)
     * @param collectedAt  thời điểm lấy số liệu, múi giờ server (như snapshot nhập qua API)
     * @param updatedAtUtc thời điểm ghi vào {@code social_accounts.last_updated_at}, giờ UTC
     */
    @Transactional
    public void recordSnapshots(Long accountId, List<CrawledMetric> metrics, LocalDateTime collectedAt,
                                LocalDateTime updatedAtUtc) {
        socialMetricRepository.saveAll(metrics.stream()
                .map(metric -> SocialMetric.builder()
                        .post(postService.getReference(metric.postId()))
                        .likes(metric.likes())
                        .shares(metric.shares())
                        .comments(metric.comments())
                        .followers(metric.followers())
                        .collectedAt(collectedAt)
                        .build())
                .toList());
        socialAccountService.markMetricsUpdated(accountId, updatedAtUtc);
    }

    @Transactional
    public void delete(Long id) {
        SocialMetric metric = findOwnedMetric(id);
        socialMetricRepository.delete(metric);
        log.info("Deleted metric {} of post {}", id, metric.getPost().getId());
    }

    private SocialMetric findOwnedMetric(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        return socialMetricRepository.findByIdAndPostUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }
}
