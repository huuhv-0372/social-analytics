package com.huuhv.socialanalytics.security;

/**
 * Cookie chứa CSRF token raw (đọc được bằng JS — {@code CookieCsrfTokenRepository.withHttpOnlyFalse}). Dùng chung giữa
 * {@code config/SecurityConfig} (đặt tên cookie) và layout ({@code <meta name="_csrf_cookie">} qua
 * {@code controller/web/LayoutModelAdvice}) để {@code static/js/csrf.js} đọc token hiện tại khi WebSocket kết nối lại.
 */
public final class CsrfCookies {

    public static final String NAME = "XSRF-TOKEN";

    private CsrfCookies() {
    }
}
