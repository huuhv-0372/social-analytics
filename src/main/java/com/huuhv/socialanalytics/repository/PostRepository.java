package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** Tìm theo id kèm điều kiện chủ sở hữu — dùng cho mọi thao tác theo id để chống IDOR. */
    Optional<Post> findByIdAndUserId(Long id, Long userId);

    Page<Post> findByUserId(Long userId, Pageable pageable);

    boolean existsByProviderAndExternalId(Provider provider, String externalId);
}
