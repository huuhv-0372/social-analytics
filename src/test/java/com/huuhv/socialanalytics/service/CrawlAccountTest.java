package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlAccountTest {

    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 7, 3, 0);

    @Test
    void tokenIsUsableUntilItExpires() {
        assertThat(account("token", NOW_UTC.plusSeconds(1)).hasUsableToken(NOW_UTC)).isTrue();
        assertThat(account("token", NOW_UTC).hasUsableToken(NOW_UTC)).isFalse();
        assertThat(account("token", NOW_UTC.minusDays(1)).hasUsableToken(NOW_UTC)).isFalse();
    }

    @Test
    void tokenWithoutExpiryIsUsable() {
        assertThat(account("token", null).hasUsableToken(NOW_UTC)).isTrue();
    }

    @Test
    void missingOrBlankTokenIsNotUsable() {
        assertThat(account(null, null).hasUsableToken(NOW_UTC)).isFalse();
        assertThat(account(" ", null).hasUsableToken(NOW_UTC)).isFalse();
    }

    @Test
    void toStringDoesNotLeakToken() {
        assertThat(account("secret-access-token", null).toString())
                .doesNotContain("secret-access-token")
                .contains("accessToken=******");
    }

    private static CrawlAccount account(String token, LocalDateTime expiresAt) {
        return new CrawlAccount(1L, 2L, Provider.FACEBOOK, token, expiresAt);
    }
}
