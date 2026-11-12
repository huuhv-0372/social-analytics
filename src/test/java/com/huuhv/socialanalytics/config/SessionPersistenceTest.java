package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import com.huuhv.socialanalytics.security.DiscardingOAuth2AuthorizedClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session lưu MySQL (Spring Session JDBC) trên Tomcat thật: request sau đọc session từ DB (không có bản trong bộ nhớ)
 * nên mỗi request đi qua bảng {@code SPRING_SESSION} giống như rơi vào instance khác / sau khi restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SessionPersistenceTest {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClientRepository;

    @Test
    void oauth2LoginStateIsStoredInDatabaseSession() throws Exception {
        HttpResponse<String> protectedPage = get("/dashboard", null);

        assertThat(protectedPage.statusCode()).isEqualTo(302);
        String setCookie = setCookie(protectedPage, SecurityConfig.SESSION_COOKIE_NAME);
        assertThat(setCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(protectedPage.headers().allValues("Set-Cookie")).noneMatch(value -> value.startsWith("JSESSIONID="));
        String sessionCookie = setCookie.substring(0, setCookie.indexOf(';'));
        String sessionId = decodeSessionId(sessionCookie);
        // Timeout lấy từ server.servlet.session.timeout (30m)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId))
                .isEqualTo(1800);

        HttpResponse<String> authorize = get("/oauth2/authorization/facebook", sessionCookie);

        assertThat(authorize.statusCode()).isEqualTo(302);
        String state = UriComponentsBuilder.fromUriString(authorize.headers().firstValue("Location").orElseThrow())
                .build().getQueryParams().getFirst("state");
        state = URLDecoder.decode(state, StandardCharsets.UTF_8);
        // Đọc lại từ DB qua bộ lọc deserialize: request đang chờ callback (state + PKCE) và saved request còn nguyên
        Session stored = sessionRepository.findById(sessionId);
        assertThat(stored).isNotNull();
        OAuth2AuthorizationRequest authorizationRequest = stored.getAttributeNames().stream()
                .map(stored::getAttribute)
                .filter(OAuth2AuthorizationRequest.class::isInstance)
                .map(OAuth2AuthorizationRequest.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(authorizationRequest.getState()).isEqualTo(state);
        assertThat(authorizationRequest.<String>getAttribute("code_verifier")).isNotBlank();
        assertThat(stored.<Object>getAttribute("SPRING_SECURITY_SAVED_REQUEST"))
                .isInstanceOfSatisfying(DefaultSavedRequest.class,
                        saved -> assertThat(saved.getRequestURI()).isEqualTo("/dashboard"));
    }

    @Test
    void sessionWrittenByAnotherInstanceAuthenticatesRequest() throws Exception {
        String sessionCookie = authenticatedSession();

        assertThat(get("/dashboard", sessionCookie).statusCode()).isEqualTo(200);
    }

    @Test
    void unreadableSecurityContextFallsBackToLoginInsteadOfError() throws Exception {
        String sessionCookie = authenticatedSession();
        int updated = jdbcTemplate.update("""
                        UPDATE SPRING_SESSION_ATTRIBUTES SET ATTRIBUTE_BYTES = ?
                        WHERE ATTRIBUTE_NAME = ?
                          AND SESSION_PRIMARY_ID = (SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?)""",
                javaSerialize(new File("not-whitelisted")),
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                decodeSessionId(sessionCookie));
        assertThat(updated).isEqualTo(1);

        HttpResponse<String> response = get("/dashboard", sessionCookie);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login"));
    }

    @Test
    void logoutDeletesSessionFromDatabase() throws Exception {
        String sessionCookie = authenticatedSession();
        String sessionId = decodeSessionId(sessionCookie);
        String xsrfSetCookie = setCookie(get("/dashboard", sessionCookie), "XSRF-TOKEN");
        String xsrfCookie = xsrfSetCookie.substring(0, xsrfSetCookie.indexOf(';'));
        String xsrfToken = xsrfCookie.substring(xsrfCookie.indexOf('=') + 1);

        HttpResponse<String> logout = client.send(HttpRequest.newBuilder(uri("/logout"))
                .header("Cookie", sessionCookie + "; " + xsrfCookie)
                .header("X-XSRF-TOKEN", xsrfToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login?logout"));
        // Cookie session bị xoá (giá trị rỗng, hết hạn ngay)
        assertThat(setCookie(logout, SecurityConfig.SESSION_COOKIE_NAME)).startsWith(SecurityConfig.SESSION_COOKIE_NAME + "=;");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId)).isZero();
    }

    @Test
    void authorizedClientsAreNotKeptInMemory() {
        assertThat(authorizedClientRepository).isInstanceOf(DiscardingOAuth2AuthorizedClientRepository.class);
    }

    /** Session đã đăng nhập do "instance khác" ghi thẳng vào DB; trả cookie {@code SESSION=...}. */
    private String authenticatedSession() {
        User user = userRepository.save(User.builder().displayName("Session test").build());
        AppOAuth2User principal = new AppOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "session-test-" + user.getId(), "name", "Session test"), "id", user.getId(),
                "Session test");
        String sessionId = saveSession(sessionRepository, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "facebook")));
        // DefaultCookieSerializer mã hoá Base64 id session trong cookie
        return SecurityConfig.SESSION_COOKIE_NAME + "="
                + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    }

    /** Kiểu session của JdbcIndexedSessionRepository không public → thao tác qua interface. */
    private static <S extends Session> String saveSession(SessionRepository<S> repository, String name, Object value) {
        S session = repository.createSession();
        session.setAttribute(name, value);
        repository.save(session);
        return session.getId();
    }

    private static String decodeSessionId(String sessionCookie) {
        String value = sessionCookie.substring(sessionCookie.indexOf('=') + 1);
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String setCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Response has no " + name + " cookie"));
    }

    private static byte[] javaSerialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private HttpResponse<String> get(String path, String cookie) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
