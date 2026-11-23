package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lỗi gửi bằng {@code sendError} được Tomcat chuyển sang {@code /error} (dispatch ERROR) và filter chain bảo mật chạy
 * lại cho lượt đó. MockMvc không làm bước này nên phải kiểm tra trên Tomcat thật: mã lỗi gốc (vd 403 CSRF) không được
 * bị luật {@code anyRequest().authenticated()} đè thành 401 / redirect login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ErrorDispatchSecurityTest {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int port;

    @Test
    void webPostWithoutCsrfTokenKeeps403OnErrorDispatch() throws Exception {
        // CsrfFilter chạy trước bước xác thực → 403 qua sendError → dispatch /error khi request chưa đăng nhập
        HttpResponse<String> response = post("/logout");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void apiPostWithoutCsrfTokenReturns403NotUnauthorized() throws Exception {
        HttpResponse<String> response = post("/api/posts");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
