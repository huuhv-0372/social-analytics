package com.huuhv.socialanalytics.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Gọi app qua HTTP thật (Tomcat của {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}) — MockMvc không đi qua
 * Tomcat nên không kiểm tra được {@code server.forward-headers-strategy=native} (RemoteIpValve).
 * <p>
 * App chỉ đăng nhập qua OAuth2 (không có HTTP Basic) nên test dùng hành vi không cần đăng nhập nhưng phụ thuộc
 * scheme/host của request: redirect tuyệt đối tới {@code /login} của {@code LoginUrlAuthenticationEntryPoint} và cờ
 * {@code Secure} của cookie {@code XSRF-TOKEN} ({@code /login} là public, cookie được set ở mọi request).
 */
final class ForwardedHeadersSupport {

    /** Header mà reverse proxy kết thúc HTTPS gửi kèm. Request test đi từ 127.0.0.1 — Tomcat coi là proxy nội bộ. */
    static final Map<String, String> PROXY_HEADERS = Map.of(
            "X-Forwarded-Proto", "https",
            "X-Forwarded-Host", "analytics.example.com");

    /** Trang web cần đăng nhập — chưa đăng nhập thì bị redirect tới {@code /login}. */
    static final String PROTECTED_PAGE = "/dashboard";

    static final String LOGIN_PAGE = "/login";

    // Không tự đi theo redirect: test cần đọc header Location
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;

    ForwardedHeadersSupport(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    HttpResponse<String> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Header {@code Set-Cookie} của cookie {@code XSRF-TOKEN}. */
    static String xsrfSetCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Response has no XSRF-TOKEN cookie"));
    }
}
