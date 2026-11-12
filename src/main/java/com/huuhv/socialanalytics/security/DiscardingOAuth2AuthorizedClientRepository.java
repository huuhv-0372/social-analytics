package com.huuhv.socialanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Không giữ {@link OAuth2AuthorizedClient} (chứa access token dạng rõ) ở bất kỳ đâu.
 * <p>
 * Mặc định Boot tạo {@code AuthenticatedPrincipalOAuth2AuthorizedClientRepository} bọc
 * {@code InMemoryOAuth2AuthorizedClientService}: sau mỗi lần đăng nhập, {@code oauth2Login} lưu token vào một map trong
 * heap, logout không xoá → token của mọi tài khoản từng đăng nhập nằm dạng rõ trong bộ nhớ tới khi restart (heap dump
 * là lộ — OWASP A02/A04), mỗi instance giữ một bản riêng. Token đã được lưu (mã hoá AES-GCM) ở
 * {@code social_accounts.access_token} trong {@code SocialAccountService.recordLogin} — đó là nguồn duy nhất; app không
 * dùng {@code OAuth2AuthorizedClientManager} / {@code @RegisteredOAuth2AuthorizedClient}.
 * <p>
 * Không thay bằng {@code HttpSessionOAuth2AuthorizedClientRepository}: session lưu trong bảng {@code SPRING_SESSION}
 * nên token sẽ bị ghi xuống DB dạng rõ.
 */
public final class DiscardingOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                     Authentication principal,
                                                                     HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        // Cố ý không lưu — xem javadoc của class
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        // Không có gì để xoá
    }
}
