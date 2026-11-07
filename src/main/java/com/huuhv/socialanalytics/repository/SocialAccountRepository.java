package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    /** Tra theo unique key {@code (provider, provider_user_id)}, lấy kèm user (1 query, không N+1). */
    @Query("""
            SELECT sa FROM SocialAccount sa JOIN FETCH sa.user
            WHERE sa.provider = :provider AND sa.providerUserId = :providerUserId""")
    Optional<SocialAccount> findWithUserByProviderAndProviderUserId(@Param("provider") Provider provider,
                                                                  @Param("providerUserId") String providerUserId);

    /** Id mọi tài khoản cho job crawl — chỉ nạp id, mỗi task tự đọc tài khoản trong transaction riêng. */
    @Query("SELECT sa.id FROM SocialAccount sa ORDER BY sa.id")
    List<Long> findAllIds();

    /**
     * Ghi thời điểm crawl thành công (UTC). UPDATE trực tiếp 1 cột: không đụng token và không đổi {@code updated_at}
     * (cột đó phản ánh lần đăng nhập/cập nhật thông tin tài khoản).
     *
     * @return số dòng được cập nhật (0 khi tài khoản vừa bị xoá)
     */
    @Modifying
    @Query("UPDATE SocialAccount sa SET sa.lastUpdatedAt = :updatedAt WHERE sa.id = :id")
    int updateLastUpdatedAt(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
