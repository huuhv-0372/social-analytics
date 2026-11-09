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

import static com.huuhv.socialanalytics.config.ForwardedHeadersSupport.PROXY_HEADERS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mặc định ({@code FORWARD_HEADERS_STRATEGY} trống, không chạy trên nền tảng cloud): app không tin
 * {@code X-Forwarded-*} — client không giả được scheme/host để điều khiển link {@code Location}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "FORWARD_HEADERS_STRATEGY=")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ForwardHeadersDisabledTest {

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
    void spoofedForwardedHeadersAreIgnoredByDefault() throws Exception {
        String csrfToken = ForwardedHeadersSupport.xsrfToken(http.get("/api/posts", PROXY_HEADERS));
        String body = """
                {"provider":"FACEBOOK","externalId":"fwd-disabled-1","url":"https://facebook.com/posts/1"}
                """;

        HttpResponse<String> response = http.postJson("/api/posts", body, csrfToken, PROXY_HEADERS);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("http://localhost:" + port + "/api/posts/"));
    }
}
