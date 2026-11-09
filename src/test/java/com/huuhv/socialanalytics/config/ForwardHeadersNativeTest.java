package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROXY_HEADERS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy sau reverse proxy kết thúc HTTPS ({@code FORWARD_HEADERS_STRATEGY=native}, đúng biến mà
 * {@code application.yml} đọc): link {@code Location} và cookie {@code XSRF-TOKEN} theo scheme/host public.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "FORWARD_HEADERS_STRATEGY=native")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ForwardHeadersNativeTest {

    @LocalServerPort
    private int port;

    @Value("${spring.security.user.name}")
    private String username;

    @Value("${spring.security.user.password}")
    private String password;

    private ForwardedHeadersSupport http;

    @BeforeEach
    void setUp() {
        http = new ForwardedHeadersSupport(port, username, password);
    }

    @Test
    void createBehindProxyReturnsPublicHttpsLocation() throws Exception {
        String csrfToken = ForwardedHeadersSupport.xsrfToken(http.get("/api/posts", PROXY_HEADERS));
        String body = """
                {"provider":"FACEBOOK","externalId":"fwd-native-1","url":"https://facebook.com/posts/1"}
                """;

        HttpResponse<String> response = http.postJson("/api/posts", body, csrfToken, PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .matches("https://analytics\\.example\\.com/api/posts/\\d+"));
    }

    @Test
    void xsrfCookieIsSecureBehindHttpsProxy() throws Exception {
        HttpResponse<String> response = http.get("/api/posts", PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ForwardedHeadersSupport.xsrfSetCookie(response)).containsIgnoringCase("; Secure");
    }

    @Test
    void xsrfCookieIsNotSecureWhenAccessedDirectlyOverHttp() throws Exception {
        HttpResponse<String> response = http.get("/api/posts", Map.of());

        assertThat(ForwardedHeadersSupport.xsrfSetCookie(response)).doesNotContainIgnoringCase("Secure");
    }
}
