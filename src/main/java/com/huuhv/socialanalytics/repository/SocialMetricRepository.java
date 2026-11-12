package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.ProviderMetricTotals;
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
     * Tổng metric theo provider của các bài thuộc user tại mốc {@code before} (giờ UTC, không tính snapshot đúng bằng
     * mốc): mỗi bài lấy snapshot mới nhất có {@code collectedAt < before} (2 snapshot cùng thời điểm → id lớn hơn), bài
     * chưa có snapshot nào trước mốc bị bỏ qua. Provider không có bài nào có snapshot → không có dòng.
     * <p>
     * Hiệu năng giống {@code PostRepository.findReportRows}: subquery {@code ORDER BY collectedAt DESC, id DESC LIMIT 1}
     * đi lùi trên index {@code (post_id, collected_at)} từ mốc {@code before} → O(số bài), không phụ thuộc số snapshot.
     * Không dùng {@code NOT EXISTS} + điều kiện {@code OR} (MySQL không range-scan được).
     */
    @Query("""
            SELECT new com.huuhv.socialanalytics.dto.response.ProviderMetricTotals(
                p.provider, SUM(m.likes), SUM(m.shares), SUM(m.comments), SUM(m.followers))
            FROM Post p
            JOIN SocialMetric m ON m.id = (
                SELECT latest.id FROM SocialMetric latest
                WHERE latest.post = p AND latest.collectedAt < :before
                ORDER BY latest.collectedAt DESC, latest.id DESC
                LIMIT 1)
            WHERE p.user.id = :userId
            GROUP BY p.provider
            """)
    List<ProviderMetricTotals> sumLatestByProvider(@Param("userId") Long userId,
                                                   @Param("before") LocalDateTime before);
}
