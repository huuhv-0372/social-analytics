package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.CryptoConfig;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link UserRepository#findBySocialAccount} — map principal OAuth2 sang user (CurrentUserService). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CryptoConfig.class})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private EntityManager entityManager;

    private User facebookUser;
    private User xUser;

    @BeforeEach
    void setUp() {
        facebookUser = userRepository.save(User.builder().email("fb@example.com").displayName("FB").build());
        xUser = userRepository.save(User.builder().displayName("X").build());
        // Cùng id phía provider trên 2 provider khác nhau là 2 người khác nhau
        socialAccountRepository.save(account(facebookUser, Provider.FACEBOOK, "42"));
        socialAccountRepository.save(account(xUser, Provider.TWITTER, "42"));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findsOwnerOfEachProviderAccount() {
        assertThat(userRepository.findBySocialAccount(Provider.FACEBOOK, "42"))
                .get().extracting(User::getId).isEqualTo(facebookUser.getId());
        assertThat(userRepository.findBySocialAccount(Provider.TWITTER, "42"))
                .get().extracting(User::getId).isEqualTo(xUser.getId());
    }

    @Test
    void unknownAccountReturnsEmpty() {
        // CurrentUserService đổi kết quả rỗng thành 401 (fail closed)
        assertThat(userRepository.findBySocialAccount(Provider.FACEBOOK, "43")).isEmpty();
    }

    @Test
    void userWithoutSocialAccountIsNeverMatched() {
        userRepository.saveAndFlush(User.builder().displayName("Orphan").build());

        assertThat(userRepository.findBySocialAccount(Provider.FACEBOOK, "")).isEmpty();
    }

    @Test
    void existsByEmailChecksExactEmail() {
        assertThat(userRepository.existsByEmail("fb@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();
    }

    private static SocialAccount account(User user, Provider provider, String providerUserId) {
        return SocialAccount.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .accessToken("token")
                .build();
    }
}
