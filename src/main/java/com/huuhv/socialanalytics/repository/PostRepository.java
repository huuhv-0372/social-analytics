package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** Tìm theo id kèm điều kiện chủ sở hữu — dùng cho mọi thao tác theo id để chống IDOR. */
    Optional<Post> findByIdAndUserId(Long id, Long userId);

    /** Chỉ kiểm tra quyền sở hữu, không load Entity. */
    boolean existsByIdAndUserId(Long id, Long userId);

    Page<Post> findByUserId(Long userId, Pageable pageable);

    /** Trùng bài trong phạm vi 1 user (unique {@code user_id, provider, external_id}). */
    boolean existsByUserIdAndProviderAndExternalId(Long userId, Provider provider, String externalId);

    /** Kiểm tra trùng khi cập nhật, bỏ qua chính bản ghi đang sửa. */
    boolean existsByUserIdAndProviderAndExternalIdAndIdNot(Long userId, Provider provider, String externalId, Long id);

    /** Bài đã có của user theo lô externalId (import upsert) — 1 query cho mỗi provider thay vì mỗi dòng. */
    List<Post> findByUserIdAndProviderAndExternalIdIn(Long userId, Provider provider, Collection<String> externalIds);

    /**
     * Báo cáo export: mọi bài của user kèm snapshot mới nhất (LEFT JOIN — bài chưa có snapshot vẫn có dòng).
     * Hai snapshot cùng {@code collectedAt} thì lấy bản có id lớn hơn để mỗi bài đúng 1 dòng.
     * <p>
     * Hiệu năng: subquery {@code ORDER BY collectedAt DESC, id DESC LIMIT 1} theo từng bài đi lùi trên index
     * {@code idx_social_metrics_post_collected (post_id, collected_at)} (InnoDB tự nối PK {@code id} vào cuối index)
     * nên chỉ đọc 1 entry/bài, sau đó JOIN theo PK — chi phí O(số bài), không phụ thuộc số snapshot của mỗi bài.
     * Outer query lọc + sắp theo unique index {@code (user_id, provider, external_id)} nên không filesort và
     * dừng ngay khi đủ {@code limit}.
     */
    @Query("""
            SELECT new com.huuhv.socialanalytics.dto.response.PostReportRow(
                p.provider, p.externalId, p.url, p.content, p.publishedAt,
                m.likes, m.shares, m.comments, m.followers, m.collectedAt)
            FROM Post p
            LEFT JOIN SocialMetric m ON m.id = (
                SELECT latest.id FROM SocialMetric latest
                WHERE latest.post = p
                ORDER BY latest.collectedAt DESC, latest.id DESC
                LIMIT 1)
            WHERE p.user.id = :userId
            ORDER BY p.provider, p.externalId, p.id
            """)
    List<PostReportRow> findReportRows(@Param("userId") Long userId, Limit limit);
}
