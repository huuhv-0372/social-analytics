package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialAccountServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-03-02T00:00:00Z");

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private UserRepository userRepository;

    private SocialAccountService service;

    @BeforeEach
    void setUp() {
        service = new SocialAccountService(socialAccountRepository, userRepository);
    }

    @Test
    void tokenExpiryIsStoredAsUtcRegardlessOfJvmTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            assertThat(SocialAccountService.toUtcDateTime(Instant.parse("2026-03-02T00:00:00Z")))
                    .isEqualTo(LocalDateTime.of(2026, 3, 2, 0, 0));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertThat(SocialAccountService.toUtcDateTime(Instant.parse("2026-03-02T00:00:00Z")))
                    .isEqualTo(LocalDateTime.of(2026, 3, 2, 0, 0));
        } finally {
            TimeZone.setDefault(original);
        }
        assertThat(SocialAccountService.toUtcDateTime(null)).isNull();
    }

    @Test
    void firstLoginCreatesUserAndSocialAccountWithTokenAndExpiry() {
        when(socialAccountRepository.findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "1234567890"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("dev@example.com")).thenReturn(false);
        stubSaves();

        User user = service.recordLogin(Provider.FACEBOOK,
                oauth2User(Map.of("id", "1234567890", "name", "Dev User", "email", "dev@example.com")),
                token("fb-access-token", EXPIRES_AT));

        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getDisplayName()).isEqualTo("Dev User");
        assertThat(user.getEmail()).isEqualTo("dev@example.com");
        SocialAccount saved = savedAccount();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getProvider()).isEqualTo(Provider.FACEBOOK);
        assertThat(saved.getProviderUserId()).isEqualTo("1234567890");
        assertThat(saved.getAccessToken()).isEqualTo("fb-access-token");
        // Thời điểm hết hạn lưu theo giờ UTC (EXPIRES_AT = 2026-03-02T00:00Z)
        assertThat(saved.getTokenExpiresAt()).isEqualTo(LocalDateTime.of(2026, 3, 2, 0, 0));
    }

    @Test
    void nextLoginUpdatesTokenAndDisplayNameOfExistingAccount() {
        User owner = User.builder().id(3L).displayName("Old Name").email("dev@example.com").build();
        SocialAccount existing = SocialAccount.builder().id(9L).user(owner).provider(Provider.FACEBOOK)
                .providerUserId("1234567890").accessToken("old-token")
                .tokenExpiresAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        when(socialAccountRepository.findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "1234567890"))
                .thenReturn(Optional.of(existing));
        when(socialAccountRepository.saveAndFlush(any(SocialAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.recordLogin(Provider.FACEBOOK,
                oauth2User(Map.of("id", "1234567890", "name", "New Name", "email", "other@example.com")),
                token("new-token", EXPIRES_AT));

        assertThat(user).isSameAs(owner);
        assertThat(owner.getDisplayName()).isEqualTo("New Name");
        // Email không đổi theo provider sau lần tạo đầu
        assertThat(owner.getEmail()).isEqualTo("dev@example.com");
        assertThat(existing.getAccessToken()).isEqualTo("new-token");
        assertThat(existing.getTokenExpiresAt()).isEqualTo(LocalDateTime.of(2026, 3, 2, 0, 0));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void emailOfAnotherUserIsNotLinked() {
        when(socialAccountRepository.findWithUserByProviderAndProviderUserId(Provider.FACEBOOK, "555"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);
        stubSaves();

        User user = service.recordLogin(Provider.FACEBOOK,
                oauth2User(Map.of("id", "555", "name", "Someone", "email", "taken@example.com")),
                token("fb-access-token", EXPIRES_AT));

        // Tạo user mới không có email, không gộp vào user đang giữ email đó
        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void xLoginWithoutEmailOrNameFallsBackToUsername() {
        when(socialAccountRepository.findWithUserByProviderAndProviderUserId(Provider.TWITTER, "2244994945"))
                .thenReturn(Optional.empty());
        stubSaves();

        User user = service.recordLogin(Provider.TWITTER,
                oauth2User(Map.of("id", "2244994945", "name", "  ", "username", "devuser")),
                token("x-access-token", null));

        assertThat(user.getDisplayName()).isEqualTo("devuser");
        assertThat(user.getEmail()).isNull();
        // Provider không trả expires_in → không có hạn
        assertThat(savedAccount().getTokenExpiresAt()).isNull();
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void displayNameIsSanitizedAndTruncatedByCodePoint() {
        String longName = "\u0007Dev\r\n" + "😀".repeat(150);
        String name = SocialAccountService.displayName(Provider.FACEBOOK, oauth2User(Map.of("id", "1", "name", longName)));

        assertThat(name).startsWith("Dev").doesNotContain("\r", "\n", "\u0007");
        assertThat(name.codePointCount(0, name.length())).isEqualTo(SocialAccountService.DISPLAY_NAME_MAX_LENGTH);
        // Không cắt đôi cặp surrogate của emoji
        assertThat(Character.isHighSurrogate(name.charAt(name.length() - 1))).isFalse();
    }

    @Test
    void displayNameFallsBackToProviderLabel() {
        assertThat(SocialAccountService.displayName(Provider.TWITTER, oauth2User(Map.of("id", "1"))))
                .isEqualTo("X user");
        assertThat(SocialAccountService.displayName(Provider.FACEBOOK, oauth2User(Map.of("id", "1"))))
                .isEqualTo("Facebook user");
    }

    @Test
    void tooLongProviderUserIdIsRejectedBeforeTouchingDatabase() {
        String id = "9".repeat(SocialAccountService.PROVIDER_USER_ID_MAX_LENGTH + 1);

        assertThatThrownBy(() -> service.recordLogin(Provider.FACEBOOK, oauth2User(Map.of("id", id)),
                token("fb-access-token", EXPIRES_AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(id);
        verifyNoInteractions(socialAccountRepository, userRepository);
    }

    private void stubSaves() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(socialAccountRepository.saveAndFlush(any(SocialAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SocialAccount savedAccount() {
        ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static OAuth2User oauth2User(Map<String, Object> attributes) {
        return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"), new HashMap<>(attributes), "id");
    }

    private static OAuth2AccessToken token(String value, Instant expiresAt) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value,
                expiresAt == null ? null : ISSUED_AT, expiresAt);
    }
}
