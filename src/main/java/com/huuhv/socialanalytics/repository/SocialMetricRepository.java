package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SocialMetricRepository extends JpaRepository<SocialMetric, Long> {

    /** Snapshot mới nhất của 1 bài viết. */
    Optional<SocialMetric> findTopByPostIdOrderByCollectedAtDesc(Long postId);

    /** Chuỗi snapshot trong khoảng thời gian (bao gồm 2 đầu), sắp xếp tăng dần để vẽ xu hướng. */
    List<SocialMetric> findByPostIdAndCollectedAtBetweenOrderByCollectedAtAsc(
            Long postId, LocalDateTime from, LocalDateTime to);
}
