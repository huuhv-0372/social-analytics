package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.ProviderDailyTotals;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    /** Định dạng DATETIME(6) MySQL đọc được trong JSON_TABLE (mốc ngày của biểu đồ, khoảng thời gian snapshot). */
    private static final DateTimeFormatter MYSQL_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final SocialMetricRepository socialMetricRepository;
    private final PostService postService;
    private final CurrentUserService currentUserService;
    private final SocialAccountService socialAccountService;
    private final DashboardEventService dashboardEventService;

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

    /**
     * Thêm 1 snapshot cho bài của user hiện tại; dashboard của owner được báo làm mới ({@code METRICS_UPDATED}, gửi sau
     * commit) như khi snapshot đến từ job crawl hay import Excel.
     */
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
        dashboardEventService.metricsUpdated(Set.of(post.getUser().getId()));
        return SocialMetricResponse.from(saved);
    }

    /**
     * Lưu kết quả crawl của 1 tài khoản: snapshot mới cho từng bài + {@code last_updated_at} của tài khoản trong
     * <b>cùng 1 transaction</b> — lỗi giữa chừng thì không lưu gì, lần chạy sau làm lại (không có trạng thái nửa vời).
     * Bài bị xoá trong lúc crawl (API gọi ngoài transaction) được bỏ qua — xem {@link #insertForExistingPosts}.
     *
     * @param metrics      số liệu đã được validate (bài thuộc tài khoản, giá trị không âm)
     * @param collectedAt  thời điểm lấy số liệu, giờ UTC (như snapshot nhập qua API — {@link UtcDateTimes})
     * @param updatedAtUtc thời điểm ghi vào {@code social_accounts.last_updated_at}, giờ UTC
     * @return số snapshot đã lưu
     */
    @Transactional
    public int recordSnapshots(Long accountId, List<CrawledMetric> metrics, LocalDateTime collectedAt,
                               LocalDateTime updatedAtUtc) {
        Inserted inserted = insertForExistingPosts(metrics.stream()
                .map(metric -> new NewSnapshot(metric.postId(), metric.likes(), metric.shares(), metric.comments(),
                        metric.followers(), collectedAt))
                .toList());
        if (inserted.skipped() > 0) {
            log.info("Account {}: skipped {} snapshots of posts deleted while crawling", accountId, inserted.skipped());
        }
        socialAccountService.markMetricsUpdated(accountId, updatedAtUtc);
        return inserted.saved();
    }

    /**
     * Lưu snapshot nhập từ file Excel của {@code ownerId} trong 1 transaction rồi báo dashboard của owner làm mới
     * ({@code METRICS_UPDATED}, gửi sau commit — rollback thì không có event).
     * <p>
     * {@code postId} của mọi request phải là bài của owner, đã tra bằng {@link PostService#findOwnedPostRefs} (không từ
     * client). Bài bị xoá giữa lúc đọc file và lúc ghi được bỏ qua — xem {@link #insertForExistingPosts}. Không đổi
     * {@code social_accounts.last_updated_at} (mốc đó chỉ dành cho job crawl).
     *
     * @param snapshots request đã validate (cùng quy tắc với {@link #create})
     * @return số snapshot đã lưu
     */
    @Transactional
    public int importSnapshots(Long ownerId, List<SocialMetricRequest> snapshots) {
        if (snapshots.isEmpty()) {
            return 0;
        }
        Inserted inserted = insertForExistingPosts(snapshots.stream()
                .map(request -> new NewSnapshot(request.postId(), request.likes(), request.shares(), request.comments(),
                        request.followers(), UtcDateTimes.toUtc(request.collectedAt())))
                .toList());
        if (inserted.skipped() > 0) {
            log.info("User {}: skipped {} imported snapshots of posts deleted during import", ownerId,
                    inserted.skipped());
        }
        int saved = inserted.saved();
        if (saved > 0) {
            dashboardEventService.metricsUpdated(Set.of(ownerId));
        }
        return saved;
    }

    /**
     * Dữ liệu file export snapshot metric của owner (projection) — xem {@link SocialMetricRepository#findReportRows}.
     * {@code ownerId} lấy từ user đang đăng nhập ở Service gọi tới, không bao giờ từ client.
     *
     * @param fromUtc / toUtc khoảng {@code collectedAt} (giờ UTC, 2 đầu tính cả); {@code null} = không giới hạn
     */
    @Transactional(readOnly = true)
    public List<SocialMetricReportRow> findReportRows(Long ownerId, LocalDateTime fromUtc, LocalDateTime toUtc,
                                                      int limit) {
        return socialMetricRepository.findReportRows(ownerId, fromUtc, toUtc, Limit.of(limit));
    }

    /**
     * Số dòng {@link #findReportRows} sẽ trả về với cùng điều kiện, đếm tối đa {@code maxCount} — rẻ (không sắp xếp,
     * dừng khi đủ) nên chạy được trước khi tính tần suất export. Xem {@link SocialMetricRepository#countReportRowsUpTo}.
     */
    @Transactional(readOnly = true)
    public long countReportRowsUpTo(Long ownerId, LocalDateTime fromUtc, LocalDateTime toUtc, int maxCount) {
        return socialMetricRepository.countReportRowsUpTo(ownerId, fromUtc, toUtc, maxCount);
    }

    /**
     * Snapshot nào trong {@code snapshots} đã có trong DB: cùng bài và cùng {@code collectedAt} tới <b>mili giây</b>
     * (ô ngày giờ Excel chỉ giữ tới mili giây, DB lưu micro giây — file export import lại vẫn được nhận ra là trùng).
     * 1 query cho cả danh sách ({@link SocialMetricRepository#findExistingSnapshotPositions}).
     *
     * @param snapshots request đã validate, {@code postId} là bài của owner đã tra từ DB (không từ client)
     * @return vị trí (từ 0) trong {@code snapshots} của các snapshot đã tồn tại
     */
    @Transactional(readOnly = true)
    public Set<Integer> findExistingSnapshotIndexes(List<SocialMetricRequest> snapshots) {
        if (snapshots.isEmpty()) {
            return Set.of();
        }
        // Mảng JSON cho JSON_TABLE — chỉ có id bài (Long) và giờ do server định dạng, vẫn bind dạng tham số
        String snapshotsJson = snapshots.stream()
                .map(snapshot -> {
                    LocalDateTime from = UtcDateTimes.toUtc(snapshot.collectedAt()).truncatedTo(ChronoUnit.MILLIS);
                    return "{\"postId\":" + snapshot.postId()
                            + ",\"from\":\"" + MYSQL_DATETIME_FORMAT.format(from)
                            + "\",\"to\":\"" + MYSQL_DATETIME_FORMAT.format(from.plus(1, ChronoUnit.MILLIS)) + "\"}";
                })
                .collect(Collectors.joining(",", "[", "]"));
        return socialMetricRepository.findExistingSnapshotPositions(snapshotsJson).stream()
                .map(position -> position.intValue() - 1)
                .collect(Collectors.toUnmodifiableSet());
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
                .map(dayEnd -> '"' + MYSQL_DATETIME_FORMAT.format(dayEnd) + '"')
                .collect(Collectors.joining(",", "[", "]"));
        return socialMetricRepository.sumLatestByProviderPerDay(userId, dayEndsJson);
    }

    /** Xoá 1 snapshot của user hiện tại; dashboard của owner được báo làm mới (sau commit) như khi thêm. */
    @Transactional
    public void delete(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        SocialMetric metric = findOwnedMetric(id, userId);
        socialMetricRepository.delete(metric);
        dashboardEventService.metricsUpdated(Set.of(userId));
        log.info("Deleted metric {} of post {}", id, metric.getPost().getId());
    }

    private SocialMetric findOwnedMetric(Long id) {
        return findOwnedMetric(id, currentUserService.getCurrentUser().getId());
    }

    private SocialMetric findOwnedMetric(Long id, Long userId) {
        return socialMetricRepository.findByIdAndPostUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    /**
     * Phần ghi dùng chung của job crawl ({@link #recordSnapshots}) và import Excel ({@link #importSnapshots}); gọi trong
     * transaction của caller. Bài bị xoá trong lúc crawl/đọc file được bỏ qua, không làm hỏng snapshot của các bài còn
     * lại: bài còn tồn tại bị khoá chia sẻ trước khi ghi ({@link PostService#lockExistingIds}) nên không thể bị xoá xen
     * giữa → INSERT không vi phạm khoá ngoại. Ghi bằng INSERT nhiều dòng ({@link SocialMetricRepository#insertAll}),
     * không qua {@code saveAll} (1 câu INSERT/snapshot vì id {@code IDENTITY}).
     */
    private Inserted insertForExistingPosts(List<NewSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return new Inserted(0, 0);
        }
        Set<Long> existingPostIds = postService.lockExistingIds(
                snapshots.stream().map(NewSnapshot::postId).distinct().toList());
        List<SocialMetric> rows = snapshots.stream()
                .filter(snapshot -> existingPostIds.contains(snapshot.postId()))
                .map(snapshot -> SocialMetric.builder()
                        .post(postService.getReference(snapshot.postId()))
                        .likes(snapshot.likes())
                        .shares(snapshot.shares())
                        .comments(snapshot.comments())
                        .followers(snapshot.followers())
                        .collectedAt(snapshot.collectedAtUtc())
                        .build())
                .toList();
        int saved = socialMetricRepository.insertAll(rows);
        return new Inserted(saved, snapshots.size() - rows.size());
    }

    /** Snapshot chờ ghi, đã validate; {@code collectedAtUtc} là giờ UTC ({@link UtcDateTimes}). */
    private record NewSnapshot(Long postId, long likes, long shares, long comments, long followers,
                               LocalDateTime collectedAtUtc) {
    }

    /**
     * @param saved   số snapshot thực sự đã lưu
     * @param skipped số snapshot bỏ qua vì bài đã bị xoá
     */
    private record Inserted(int saved, int skipped) {
    }
}
