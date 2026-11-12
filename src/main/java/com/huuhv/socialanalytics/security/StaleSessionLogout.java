package com.huuhv.socialanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * Đăng xuất session đã đăng nhập nhưng không còn ánh xạ được tới {@code User} trong DB (user bị xoá, DB bị reset) —
 * fail closed (OWASP A01/A07). Dùng chung cho {@code config/StaleSessionFilter} (phát hiện trước controller) và
 * {@code exception/WebExceptionHandler} (user bị xoá giữa request) để 2 nơi luôn làm cùng các bước và cùng log.
 * <p>
 * Huỷ session + xoá {@code SecurityContext}; không redirect — caller tự quyết định phản hồi. Log provider (hằng số cấu
 * hình), không log id session / cookie (OWASP A09).
 */
@Slf4j
public final class StaleSessionLogout {

    // Không giữ trạng thái theo request → dùng chung an toàn giữa các thread
    private static final SecurityContextLogoutHandler LOGOUT_HANDLER = new SecurityContextLogoutHandler();

    private StaleSessionLogout() {
    }

    public static void logout(HttpServletRequest request, HttpServletResponse response,
                              Authentication authentication) {
        LOGOUT_HANDLER.logout(request, response, authentication);
        log.warn("Invalidated stale {} session: authenticated user no longer exists", registrationId(authentication));
    }

    private static String registrationId(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId()
                : "unknown";
    }
}
