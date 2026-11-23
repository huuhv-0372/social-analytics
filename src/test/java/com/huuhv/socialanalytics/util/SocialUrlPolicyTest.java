package com.huuhv.socialanalytics.util;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SocialUrlPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "FACEBOOK, https://facebook.com/posts/1",
            "FACEBOOK, https://www.facebook.com/page/posts/1",
            "FACEBOOK, https://M.FACEBOOK.COM/story.php?id=1",
            "TWITTER, https://x.com/user/status/1",
            "TWITTER, https://twitter.com/user/status/1",
            "TWITTER, https://mobile.twitter.com/user/status/1",
            "TWITTER, https://x.com:443/user/status/1"        // port https tường minh
    })
    void allowsHttpsUrlOnProviderDomain(Provider provider, String url) {
        assertThat(SocialUrlPolicy.isAllowed(provider, url)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "FACEBOOK, http://facebook.com/posts/1",          // không phải https
            "FACEBOOK, https://x.com/user/status/1",          // domain của provider khác
            "FACEBOOK, https://evilfacebook.com/posts/1",     // chỉ trùng hậu tố, không phải subdomain
            "FACEBOOK, https://facebook.com.evil.com/posts/1",
            "FACEBOOK, https://user@facebook.com/posts/1",    // có userinfo
            "FACEBOOK, https://m.facebook.com:8443/posts/1",  // port không chuẩn
            "TWITTER, https://x.com:22/user/status/1",
            "TWITTER, https://169.254.169.254/latest/meta-data",
            "TWITTER, file:///etc/passwd",
            "TWITTER, https://x.com/a b",                     // URI không hợp lệ
            "TWITTER, not-a-url"
    })
    void rejectsUrlOutsideWhitelist(Provider provider, String url) {
        assertThat(SocialUrlPolicy.isAllowed(provider, url)).isFalse();
    }
}
