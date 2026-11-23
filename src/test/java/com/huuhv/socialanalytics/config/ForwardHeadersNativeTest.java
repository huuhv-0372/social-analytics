package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.LOGIN_PAGE;
import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROTECTED_PAGE;
import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROXY_HEADERS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy sau reverse proxy kết thúc HTTPS ({@code FORWARD_HEADERS_STRATEGY=native}, đúng biến mà
 * {@code application.yml} đọc): link tuyệt đối và cookie {@code XSRF-TOKEN} theo scheme/host public.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "FORWARD_HEADERS_STRATEGY=native")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ForwardHeadersNativeTest {

    @LocalServerPort
    private int port;

    private ForwardedHeadersSupport http;

    @BeforeEach
    void setUp() {
        http = new ForwardedHeadersSupport(port);
    }

    @Test
    void loginRedirectBehindProxyUsesPublicHttpsUrl() throws Exception {
        HttpResponse<String> response = http.get(PROTECTED_PAGE, PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValue("https://analytics.example.com" + LOGIN_PAGE);
    }

    @Test
    void xsrfCookieIsSecureBehindHttpsProxy() throws Exception {
        HttpResponse<String> response = http.get(LOGIN_PAGE, PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ForwardedHeadersSupport.xsrfSetCookie(response)).containsIgnoringCase("; Secure");
    }

    @Test
    void xsrfCookieIsNotSecureWhenAccessedDirectlyOverHttp() throws Exception {
        HttpResponse<String> response = http.get(LOGIN_PAGE, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ForwardedHeadersSupport.xsrfSetCookie(response)).doesNotContainIgnoringCase("Secure");
    }
}
