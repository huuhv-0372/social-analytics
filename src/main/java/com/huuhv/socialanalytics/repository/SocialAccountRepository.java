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

    /**
     * Id mọi tài khoản cho job crawl — chỉ nạp id, mỗi task tự đọc tài khoản trong transaction riêng.
     * <p>
     * Thứ tự = thứ tự job gửi task: tài khoản lâu chưa cập nhật nhất trước (chưa từng cập nhật — {@code NULL} — đứng
     * đầu), trùng thời điểm thì theo id. Lần chạy quá hạn bỏ các tài khoản cuối danh sách; sort theo id thì lần nào
     * cũng bỏ đúng nhóm id lớn (tài khoản mới đăng nhập không bao giờ được cập nhật), sort theo
     * {@code last_updated_at} thì nhóm bị bỏ lần này lên đầu lần sau.
     */
    @Query("SELECT sa.id FROM SocialAccount sa ORDER BY sa.lastUpdatedAt ASC NULLS FIRST, sa.id ASC")
    List<Long> findAllIdsStalestFirst();

    /**
     * Ghi thời điểm crawl thành công (UTC). UPDATE trực tiếp 1 cột: không đụng token và không đổi {@code updated_at}
     * (cột đó phản ánh lần đăng nhập/cập nhật thông tin tài khoản).
     *
     * @return số dòng được cập nhật (0 khi tài khoản vừa bị xoá)
     */
    @Modifying
    @Query("UPDATE SocialAccount sa SET sa.lastUpdatedAt = :updatedAt WHERE sa.id = :id")
    int updateLastUpdatedAt(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Lần crawl metrics thành công gần nhất trong các tài khoản <b>của 1 user</b> (giờ UTC).
     *
     * @return rỗng khi user chưa có tài khoản nào được crawl ({@code MAX} trên toàn {@code NULL})
     */
    @Query("SELECT MAX(sa.lastUpdatedAt) FROM SocialAccount sa WHERE sa.user.id = :userId")
    Optional<LocalDateTime> findMaxLastUpdatedAtByUserId(@Param("userId") Long userId);
}
