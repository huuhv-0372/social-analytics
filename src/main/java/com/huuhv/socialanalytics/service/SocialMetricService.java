package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.ProviderDailyTotals;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.util.PageableUtils;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** Định dạng DATETIME(6) MySQL đọc được trong JSON_TABLE. */
    private static final DateTimeFormatter DAY_END_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final SocialMetricRepository socialMetricRepository;
    private final PostService postService;
    private final CurrentUserService currentUserService;
    private final SocialAccountService socialAccountService;

    @Transactional(readOnly = true)
    public PageResponse<SocialMetricResponse> findByPost(Long postId, Instant from, Instant to,
                                                         Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        postService.checkOwnedPost(postId);
        Pageable safePageable = PageableUtils.withAllowedSort(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return PageResponse.from(socialMetricRepository
                .search(postId, UtcDateTimes.toUtc(from), UtcDateTimes.toUtc(to), safePageable)
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
                .collectedAt(UtcDateTimes.toUtc(request.collectedAt()))
                .build());
        return SocialMetricResponse.from(saved);
    }

    /**
     * Lưu kết quả crawl của 1 tài khoản: snapshot mới cho từng bài + {@code last_updated_at} của tài khoản trong
     * <b>cùng 1 transaction</b> — lỗi giữa chừng thì không lưu gì, lần chạy sau làm lại (không có trạng thái nửa vời).
     * <p>
     * Bài bị xoá trong lúc crawl (API gọi ngoài transaction) được bỏ qua, không làm hỏng snapshot của các bài còn lại:
     * bài còn tồn tại bị khoá chia sẻ trước khi ghi ({@link PostService#lockExistingIds}) nên không thể bị xoá xen giữa
     * → INSERT không vi phạm khoá ngoại. Ghi bằng INSERT nhiều dòng ({@link SocialMetricRepository#insertAll}), không
     * qua {@code saveAll} (1 câu INSERT/snapshot vì id {@code IDENTITY}).
     *
     * @param metrics      số liệu đã được validate (bài thuộc tài khoản, giá trị không âm)
     * @param collectedAt  thời điểm lấy số liệu, giờ UTC (như snapshot nhập qua API — {@link UtcDateTimes})
     * @param updatedAtUtc thời điểm ghi vào {@code social_accounts.last_updated_at}, giờ UTC
     * @return số snapshot đã lưu
     */
    @Transactional
    public int recordSnapshots(Long accountId, List<CrawledMetric> metrics, LocalDateTime collectedAt,
                               LocalDateTime updatedAtUtc) {
        Set<Long> existingPostIds = postService.lockExistingIds(metrics.stream().map(CrawledMetric::postId).toList());
        List<SocialMetric> snapshots = metrics.stream()
                .filter(metric -> existingPostIds.contains(metric.postId()))
                .map(metric -> SocialMetric.builder()
                        .post(postService.getReference(metric.postId()))
                        .likes(metric.likes())
                        .shares(metric.shares())
                        .comments(metric.comments())
                        .followers(metric.followers())
                        .collectedAt(collectedAt)
                        .build())
                .toList();
        if (snapshots.size() < metrics.size()) {
            log.info("Account {}: skipped {} snapshots of posts deleted while crawling", accountId,
                    metrics.size() - snapshots.size());
        }
        int saved = socialMetricRepository.insertAll(snapshots);
        socialAccountService.markMetricsUpdated(accountId, updatedAtUtc);
        return saved;
    }

    /**
     * Metric theo provider của các bài thuộc {@code userId} tại từng mốc (giờ UTC), 1 query cho mọi mốc — xem
     * {@link SocialMetricRepository#sumLatestByProviderPerDay}. Caller chịu trách nhiệm {@code userId} là owner đang
     * đăng nhập.
     *
     * @param dayEndsUtc các mốc (thường là đầu ngày kế tiếp); kết quả có {@code dayIndex} = vị trí trong danh sách, từ 1
     */
    @Transactional(readOnly = true)
    public List<ProviderDailyTotals> sumLatestByProviderPerDay(Long userId, List<LocalDateTime> dayEndsUtc) {
        if (dayEndsUtc.isEmpty()) {
            return List.of();
        }
        // Mảng JSON cho JSON_TABLE, dựng từ LocalDateTime do server tính (không có input người dùng) và vẫn bind tham số
        String dayEndsJson = dayEndsUtc.stream()
                .map(dayEnd -> '"' + DAY_END_FORMAT.format(dayEnd) + '"')
                .collect(Collectors.joining(",", "[", "]"));
        return socialMetricRepository.sumLatestByProviderPerDay(userId, dayEndsJson);
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
