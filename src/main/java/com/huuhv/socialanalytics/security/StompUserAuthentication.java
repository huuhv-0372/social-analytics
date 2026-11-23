package com.huuhv.socialanalytics.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Principal của 1 phiên STOMP: bọc {@link Authentication} của session HTTP lúc handshake, chỉ đổi {@link #getName()}
 * thành <b>id user nội bộ</b>. Spring gửi tin tới {@code /user/queue/...} theo tên principal; tên mặc định của OAuth2
 * là id phía provider — id Facebook và id X có thể trùng nhau, gửi theo tên đó có thể tới nhầm user.
 * <p>
 * Vẫn là {@link Authentication} (đã xác thực, cùng quyền) để {@code SecurityContextChannelInterceptor} và luật phân
 * quyền frame ({@code config/WebSocketSecurityConfig}) hoạt động như cũ. Không lộ credentials của principal gốc.
 */
public final class StompUserAuthentication extends AbstractAuthenticationToken {

    private final transient Object principal;
    private final String userId;

    public StompUserAuthentication(Authentication authentication, long userId) {
        super(authentication.getAuthorities());
        this.principal = authentication.getPrincipal();
        this.userId = Long.toString(userId);
        super.setAuthenticated(authentication.isAuthenticated());
    }

    @Override
    public String getName() {
        return userId;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
