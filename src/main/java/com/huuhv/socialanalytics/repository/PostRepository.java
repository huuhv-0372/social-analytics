package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
