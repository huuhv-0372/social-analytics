package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Test trên Tomcat thật: tạo session đã đăng nhập OAuth2 ghi thẳng vào bảng session (như "instance khác" ghi) thay vì
 * chạy luồng OAuth2 với provider thật. Dùng chung cho các test trên Tomcat thật ({@code SessionPersistenceTest},
 * {@code WebSocketSecurityIntegrationTest}, {@code EndToEndFlowTest}).
 */
public final class AuthenticatedSessions {

    private AuthenticatedSessions() {
    }

    /**
     * Session đã đăng nhập của 1 user.
     *
     * @param cookie cookie {@code SESSION=...} gửi kèm request
     * @param userId id user nội bộ (tên principal STOMP)
     */
    public record LoggedInSession(String cookie, long userId) {
    }

    /** Tạo user mới + session đăng nhập của user đó. */
    public static LoggedInSession create(UserRepository userRepository, SessionRepository<? extends Session> sessionRepository) {
        User user = userRepository.save(User.builder().displayName("Session test").build());
        AppOAuth2User principal = new AppOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "session-test-" + user.getId(), "name", "Session test"), "id", user.getId(),
                "Session test");
        String sessionId = saveSession(sessionRepository, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "facebook")));
        // DefaultCookieSerializer mã hoá Base64 id session trong cookie
        return new LoggedInSession(SecurityConfig.SESSION_COOKIE_NAME + "="
                + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)), user.getId());
    }

    /** Kiểu session của JdbcIndexedSessionRepository không public → thao tác qua interface. */
    private static <S extends Session> String saveSession(SessionRepository<S> repository, String name, Object value) {
        S session = repository.createSession();
        session.setAttribute(name, value);
        repository.save(session);
        return session.getId();
    }
}
