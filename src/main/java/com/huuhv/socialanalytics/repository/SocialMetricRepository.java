package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialMetric;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long> {

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
}
