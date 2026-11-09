package com.huuhv.socialanalytics.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Gọi app qua HTTP thật (Tomcat của {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}) — MockMvc không đi qua
 * Tomcat nên không kiểm tra được {@code server.forward-headers-strategy=native} (RemoteIpValve).
 */
final class ForwardedHeadersSupport {

    /** Header mà reverse proxy kết thúc HTTPS gửi kèm. Request test đi từ 127.0.0.1 — Tomcat coi là proxy nội bộ. */
    static final Map<String, String> PROXY_HEADERS = Map.of(
            "X-Forwarded-Proto", "https",
            "X-Forwarded-Host", "analytics.example.com");

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String basicAuth;

    ForwardedHeadersSupport(int port, String username, String password) {
        this.baseUrl = "http://localhost:" + port;
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    HttpResponse<String> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        return send(request(path, headers).GET());
    }

    /** POST JSON kèm CSRF: cookie {@code XSRF-TOKEN} + header {@code X-XSRF-TOKEN} như Swagger UI / Postman. */
    HttpResponse<String> postJson(String path, String body, String csrfToken, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Header {@code Set-Cookie} của cookie {@code XSRF-TOKEN}. */
    static String xsrfSetCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Response has no XSRF-TOKEN cookie"));
    }

    static String xsrfToken(HttpResponse<?> response) {
        String cookie = xsrfSetCookie(response);
        return cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", basicAuth);
        headers.forEach(builder::header);
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
