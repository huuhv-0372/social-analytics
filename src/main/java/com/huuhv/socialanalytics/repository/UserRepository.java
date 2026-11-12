package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Chỉ dùng để không gán trùng email cho user mới — không tra/gộp tài khoản theo email (OWASP A07). */
    boolean existsByEmail(String email);

    /** User sở hữu tài khoản mạng xã hội {@code (provider, providerUserId)} — dùng unique index của social_accounts. */
    @Query("""
            SELECT sa.user FROM SocialAccount sa
            WHERE sa.provider = :provider AND sa.providerUserId = :providerUserId""")
    Optional<User> findBySocialAccount(@Param("provider") Provider provider,
                                       @Param("providerUserId") String providerUserId);
}
