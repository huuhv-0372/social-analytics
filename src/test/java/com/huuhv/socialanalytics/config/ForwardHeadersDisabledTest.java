package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.LOGIN_PAGE;
import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROTECTED_PAGE;
import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROXY_HEADERS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mặc định ({@code FORWARD_HEADERS_STRATEGY} trống, không chạy trên nền tảng cloud): app không tin
 * {@code X-Forwarded-*} — client không giả được scheme/host để điều khiển link tuyệt đối (redirect, {@code Location}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "FORWARD_HEADERS_STRATEGY=")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ForwardHeadersDisabledTest {

    @LocalServerPort
    private int port;

    private ForwardedHeadersSupport http;

    @BeforeEach
    void setUp() {
        http = new ForwardedHeadersSupport(port);
    }

    @Test
    void spoofedForwardedHeadersAreIgnoredByDefault() throws Exception {
        HttpResponse<String> response = http.get(PROTECTED_PAGE, PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValue("http://localhost:" + port + LOGIN_PAGE);
        assertThat(ForwardedHeadersSupport.xsrfSetCookie(response)).doesNotContainIgnoringCase("Secure");
    }
}
