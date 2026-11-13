package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.ProviderDailyTotals;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
import com.huuhv.socialanalytics.entity.SocialMetric;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long>, SocialMetricRepositoryCustom {

    /** Snapshot mới nhất của 1 bài viết. */
    Optional<SocialMetric> findTopByPostIdOrderByCollectedAtDesc(Long postId);

    /** Tìm theo id kèm điều kiện chủ sở hữu của bài viết — chống IDOR. */
    Optional<SocialMetric> findByIdAndPostUserId(Long id, Long userId);

    /** Snapshot của 1 bài viết, lọc khoảng thời gian tuỳ chọn (giờ UTC; {@code null} = không giới hạn). */
    @Query(value = """
            SELECT m FROM SocialMetric m
            WHERE m.post.id = :postId
              AND (:from IS NULL OR m.collectedAt >= :from)
              AND (:to IS NULL OR m.collectedAt <= :to)
            """,
            countQuery = """
            SELECT COUNT(m) FROM SocialMetric m
            WHERE m.post.id = :postId
              AND (:from IS NULL OR m.collectedAt >= :from)
              AND (:to IS NULL OR m.collectedAt <= :to)
            """)
    Page<SocialMetric> search(@Param("postId") Long postId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              Pageable pageable);

    /**
     * File export snapshot metric của user (projection, không load Entity): mọi snapshot của các bài thuộc
     * {@code userId}, lọc khoảng {@code collectedAt} tuỳ chọn (giờ UTC, 2 đầu tính cả; {@code null} = không giới hạn),
     * sắp theo bài rồi thời gian. Caller truyền {@code limit} = giới hạn + 1 để biết có vượt giới hạn hay không.
     * <p>
     * Hiệu năng: JOIN từ {@code posts} (unique index bắt đầu bằng {@code user_id}) sang
     * {@code idx_social_metrics_post_collected (post_id, collected_at)} — lọc khoảng thời gian bằng range trên index của
     * từng bài; sắp xếp cần filesort (top-N vì có LIMIT) trên các snapshot khớp điều kiện → khoảng thời gian càng hẹp
     * càng rẻ.
     */
    @Query("""
            SELECT new com.huuhv.socialanalytics.dto.response.SocialMetricReportRow(
                p.provider, p.externalId, m.likes, m.shares, m.comments, m.followers, m.collectedAt)
            FROM SocialMetric m JOIN m.post p
            WHERE p.user.id = :userId
              AND (:from IS NULL OR m.collectedAt >= :from)
              AND (:to IS NULL OR m.collectedAt <= :to)
            ORDER BY p.provider, p.externalId, m.collectedAt, m.id
            """)
    List<SocialMetricReportRow> findReportRows(@Param("userId") Long userId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               Limit limit);

    /**
     * Số snapshot {@link #findReportRows} sẽ trả về, đếm tối đa {@code maxCount} (dừng sớm, không sắp xếp): kiểm tra
     * giới hạn dòng của export trước khi tính tần suất và trước câu query có filesort. Chi phí ≤ {@code maxCount} lần
     * đọc index, không phụ thuộc tổng số snapshot của user. Cùng điều kiện lọc với {@link #findReportRows} — sửa 1 câu
     * thì sửa cả 2.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM social_metrics m
                JOIN posts p ON p.id = m.post_id
                WHERE p.user_id = :userId
                  AND (:from IS NULL OR m.collected_at >= :from)
                  AND (:to IS NULL OR m.collected_at <= :to)
                LIMIT :maxCount
            ) AS matched
            """, nativeQuery = true)
    long countReportRowsUpTo(@Param("userId") Long userId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("maxCount") int maxCount);

    /**
     * Vị trí (đánh từ 1, theo thứ tự trong mảng) của các snapshot <b>đã có</b> trong DB, trong danh sách ứng viên
     * {@code snapshotsJson} = mảng JSON {@code [{"postId": 1, "from": "yyyy-MM-dd HH:mm:ss.SSSSSS", "to": "..."}]} —
     * snapshot trùng khi cùng bài và {@code collected_at} thuộc {@code [from, to)} (giờ UTC). 1 query cho cả danh sách:
     * {@code JSON_TABLE} (MySQL 8) dựng bảng tạm, mỗi ứng viên 1 lần tra range trên
     * {@code idx_social_metrics_post_collected (post_id, collected_at)}.
     * <p>
     * JSON do Service dựng từ id bài đã kiểm tra quyền sở hữu + giờ đã parse (không có chuỗi người dùng nhập) và vẫn
     * truyền dạng tham số bind.
     */
    @Query(value = """
            SELECT candidate.idx
            FROM JSON_TABLE(:snapshotsJson, '$[*]' COLUMNS (
                    idx FOR ORDINALITY,
                    post_id BIGINT PATH '$.postId',
                    collected_from DATETIME(6) PATH '$.from',
                    collected_to DATETIME(6) PATH '$.to')) AS candidate
            WHERE EXISTS (
                SELECT 1 FROM social_metrics m
                WHERE m.post_id = candidate.post_id
                  AND m.collected_at >= candidate.collected_from
                  AND m.collected_at < candidate.collected_to)
            """, nativeQuery = true)
    List<Number> findExistingSnapshotPositions(@Param("snapshotsJson") String snapshotsJson);

    /**
     * Metric theo provider của các bài thuộc user tại <b>từng</b> mốc trong {@code dayEndsJson} — 1 query cho cả biểu đồ
     * (thay vì 1 query/ngày). Tại mỗi mốc (giờ UTC, không tính snapshot đúng bằng mốc), mỗi bài lấy snapshot mới nhất có
     * {@code collected_at < mốc} (2 snapshot cùng thời điểm → id lớn hơn); bài chưa có snapshot trước mốc bị bỏ qua
     * (provider không có bài nào → không có dòng cho mốc đó). Bài không còn được crawl (vd đã xoá trên nền tảng) giữ
     * snapshot cuối cùng mãi (carry-forward, chủ ý — tránh biểu đồ tụt giả khi token hết hạn và job bỏ qua tài khoản vài
     * ngày); xoá bài trong app thì snapshot bị xoá theo (FK cascade).
     * <ul>
     *     <li>likes / shares / comments: tổng các bài ({@code SUM}).</li>
     *     <li>followers (số người theo dõi tác giả, lặp trên mọi bài của tài khoản): giá trị của snapshot mới nhất theo
     *     {@code collected_at} trong provider ({@code FIRST_VALUE … OVER}) — cộng sẽ đếm lặp, MAX sẽ để số cũ của bài
     *     không còn được crawl đè số mới.</li>
     * </ul>
     * Mốc truyền dạng mảng JSON {@code ["2026-11-06 17:00:00.000000", …]} (tham số bind, không nối chuỗi) →
     * {@code JSON_TABLE} của MySQL 8; {@code day_index} = vị trí mốc, bắt đầu từ 1.
     * <p>
     * Hiệu năng: subquery {@code ORDER BY collected_at DESC, id DESC LIMIT 1} đi lùi trên index
     * {@code (post_id, collected_at)} từ mỗi mốc → O(số mốc × số bài) lần tra index trong <b>1</b> round-trip, không
     * phụ thuộc số snapshot (gom nhóm mọi snapshot trong khoảng sẽ đọc ~24 dòng/bài/ngày khi crawl mỗi giờ). Không dùng
     * {@code NOT EXISTS} + {@code OR} (MySQL không range-scan được). Tổng hợp nằm hết trong SQL.
     */
    @Query(value = """
            WITH latest AS (
                SELECT d.day_index, p.provider, m.likes, m.shares, m.comments,
                       FIRST_VALUE(m.followers) OVER (
                           PARTITION BY d.day_index, p.provider
                           ORDER BY m.collected_at DESC, m.id DESC) AS latest_followers
                FROM JSON_TABLE(:dayEndsJson, '$[*]' COLUMNS (
                         day_index FOR ORDINALITY,
                         day_end DATETIME(6) PATH '$')) AS d
                JOIN posts p ON p.user_id = :userId
                JOIN social_metrics m ON m.id = (
                    SELECT s.id FROM social_metrics s
                    WHERE s.post_id = p.id AND s.collected_at < d.day_end
                    ORDER BY s.collected_at DESC, s.id DESC
                    LIMIT 1)
            )
            SELECT CAST(day_index AS SIGNED) AS dayIndex,
                   provider AS provider,
                   CAST(SUM(likes) AS SIGNED) AS likes,
                   CAST(SUM(shares) AS SIGNED) AS shares,
                   CAST(SUM(comments) AS SIGNED) AS comments,
                   CAST(MAX(latest_followers) AS SIGNED) AS followers
            FROM latest
            GROUP BY day_index, provider
            ORDER BY day_index, provider
            """, nativeQuery = true)
    List<ProviderDailyTotals> sumLatestByProviderPerDay(@Param("userId") Long userId,
                                                        @Param("dayEndsJson") String dayEndsJson);
}
