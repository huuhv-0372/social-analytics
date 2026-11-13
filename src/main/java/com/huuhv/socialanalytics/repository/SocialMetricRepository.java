package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.ProviderDailyTotals;
import com.huuhv.socialanalytics.entity.SocialMetric;
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
