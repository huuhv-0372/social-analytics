package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.util.LogSanitizer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Kết quả đăng nhập OAuth2: ghi log sự kiện bảo mật (OWASP A09) rồi redirect tới URL nội bộ cố định
 * (không theo tham số hay saved request → không open redirect, OWASP A01).
 * Chỉ log provider, id user phía provider và mã lỗi chuẩn OAuth2 — không log token, code, state hay cookie.
 */
@Slf4j
class OAuth2LoginResultHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final SimpleUrlAuthenticationSuccessHandler successDelegate;
    private final SimpleUrlAuthenticationFailureHandler failureDelegate;

    OAuth2LoginResultHandler(String successUrl, String failureUrl) {
        this.successDelegate = new SimpleUrlAuthenticationSuccessHandler(successUrl);
        this.successDelegate.setAlwaysUseDefaultTargetUrl(true);
        this.failureDelegate = new SimpleUrlAuthenticationFailureHandler(failureUrl);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String provider = authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId()
                : "unknown";
        log.info("Login succeeded: provider={} user={}", LogSanitizer.stripLineBreaks(provider),
                LogSanitizer.stripLineBreaks(authentication.getName()));
        successDelegate.onAuthenticationSuccess(request, response, authentication);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        // Mã lỗi có thể đến từ tham số callback (?error=...) → bỏ CR/LF trước khi log
        String errorCode = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("Login failed: error={} remoteAddr={}", LogSanitizer.stripLineBreaks(errorCode),
                request.getRemoteAddr());
        failureDelegate.onAuthenticationFailure(request, response, exception);
    }
}
