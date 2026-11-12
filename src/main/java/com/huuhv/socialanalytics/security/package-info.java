/**
 * Thành phần bảo mật (không phải {@code @Configuration}) được các class trong {@code config} lắp vào filter chain:
 * entry point / handler 401–403 dạng {@code ProblemDetail}, xử lý CSRF token, kết quả đăng nhập OAuth2, principal
 * {@link com.huuhv.socialanalytics.security.AppOAuth2User}, bộ lọc deserialize attribute session, repository bỏ
 * {@code OAuth2AuthorizedClient}.
 * <p>
 * Phụ thuộc một chiều: {@code config}, {@code service}, {@code controller} dùng package này; package này không import
 * {@code config}, {@code service}, {@code exception} (không tạo vòng). Vì vậy {@code config/StaleSessionFilter} (cần
 * {@code CurrentUserService} + {@code AuthenticationRequiredException}) ở lại {@code config}.
 */
package com.huuhv.socialanalytics.security;
