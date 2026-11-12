package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.security.StaleSessionLogout;
import com.huuhv.socialanalytics.service.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Huỷ session đã đăng nhập nhưng không còn ánh xạ được tới {@code User} trong DB (user bị xoá, DB bị reset, tài khoản
 * mạng xã hội không còn) — OWASP A01/A07, fail closed.
 * <p>
 * Không có filter này, session cũ vẫn "đã đăng nhập": trang web mở bình thường, {@code /login} chuyển về
 * {@code /dashboard}, chỉ API nhận 401 → user bị kẹt tới khi tự bấm Logout. Filter dùng lại đúng quy tắc của
 * {@link CurrentUserService#getCurrentUser()}; không xác định được user → logout (huỷ session, xoá
 * {@code SecurityContext}) rồi cho request đi tiếp như ẩn danh: trang web được redirect {@code /login}, REST nhận 401
 * {@code ProblemDetail} qua các entry point sẵn có của {@code SecurityConfig}.
 * <p>
 * Đặt trước {@code AnonymousAuthenticationFilter} (sau khi khôi phục context từ session và sau {@code LogoutFilter}).
 * Bỏ qua static resource (không tốn query cho css/js) và dispatch ERROR/ASYNC (mặc định của
 * {@link OncePerRequestFilter}). Lỗi DB khi kiểm tra không bị nuốt → request thất bại (500), không cho qua.
 * <p>
 * Request REST đã qua filter vẫn bị {@code CurrentUserService} kiểm tra lần nữa ở Service (thêm 1 query theo khoá chính):
 * chấp nhận để Service tự fail closed khi chạy ngoài filter chain và khi user bị xoá giữa request.
 */
class StaleSessionFilter extends OncePerRequestFilter {

    private static final RequestMatcher STATIC_RESOURCES = staticResources();

    private final CurrentUserService currentUserService;

    StaleSessionFilter(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return STATIC_RESOURCES.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated()) {
            try {
                currentUserService.getCurrentUser();
            } catch (AuthenticationRequiredException e) {
                StaleSessionLogout.logout(request, response, authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private static RequestMatcher staticResources() {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(SecurityConfig.STATIC_RESOURCE_PATTERNS.stream()
                .map(path::matcher)
                .map(RequestMatcher.class::cast)
                .toList());
    }
}
