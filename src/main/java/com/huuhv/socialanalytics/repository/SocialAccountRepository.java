package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    /** Tra theo unique key {@code (provider, provider_user_id)}, lấy kèm user (1 query, không N+1). */
    @Query("""
            SELECT sa FROM SocialAccount sa JOIN FETCH sa.user
            WHERE sa.provider = :provider AND sa.providerUserId = :providerUserId""")
    Optional<SocialAccount> findWithUserByProviderAndProviderUserId(@Param("provider") Provider provider,
                                                                  @Param("providerUserId") String providerUserId);
}
