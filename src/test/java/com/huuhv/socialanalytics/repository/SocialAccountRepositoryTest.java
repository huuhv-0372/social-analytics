package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.CryptoConfig;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Query tra tài khoản lúc đăng nhập — {@code SocialAccount} dùng converter mã hoá nên cần {@link CryptoConfig}. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CryptoConfig.class})
class SocialAccountRepositoryTest {

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().displayName("Owner").build());
        socialAccountRepository.saveAndFlush(account(owner, Provider.FACEBOOK, "1234567890"));
        entityManager.clear();
    }

    @Test
    void findsAccountByProviderAndProviderUserIdWithUserFetched() {
        SocialAccount account = socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "1234567890")
                .orElseThrow();

        // JOIN FETCH: user đã nạp cùng query (không lazy-load thêm query khi recordLogin cập nhật tên hiển thị)
        assertThat(Hibernate.isInitialized(account.getUser())).isTrue();
        assertThat(account.getUser().getId()).isEqualTo(owner.getId());
        assertThat(account.getAccessToken()).isEqualTo("token-1234567890");
    }

    @Test
    void sameProviderUserIdOnAnotherProviderIsNotMatched() {
        // Id phía provider chỉ duy nhất trong phạm vi 1 provider
        assertThat(socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.TWITTER, "1234567890")).isEmpty();
    }

    @Test
    void unknownProviderUserIdReturnsEmpty() {
        assertThat(socialAccountRepository
                .findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "999")).isEmpty();
    }

    @Test
    void duplicateProviderAccountViolatesUniqueKey() {
        // Lưới an toàn cho đăng nhập song song: CustomOAuth2UserService bắt lỗi này rồi thử lại
        User other = userRepository.save(User.builder().displayName("Other").build());

        assertThatThrownBy(() -> socialAccountRepository.saveAndFlush(account(other, Provider.FACEBOOK, "1234567890")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static SocialAccount account(User user, Provider provider, String providerUserId) {
        return SocialAccount.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .accessToken("token-" + providerUserId)
                .build();
    }
}
