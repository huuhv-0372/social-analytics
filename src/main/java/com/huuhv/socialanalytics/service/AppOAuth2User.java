package com.huuhv.socialanalytics.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.util.Assert;

import java.io.Serial;
import java.util.Collection;
import java.util.Map;

/**
 * Principal OAuth2 của app: thuộc tính từ provider + id nội bộ của {@code User} đã lưu lúc đăng nhập
 * ({@link CustomOAuth2UserService}). {@link CurrentUserService} lấy owner từ {@link #getUserId()} mà không cần query
 * {@code social_accounts} ở mỗi request.
 * <p>
 * {@code userId} do server gán sau khi lưu DB, nằm trong session phía server — client không sửa được (OWASP A01).
 * Lưu trong session nên phải {@link java.io.Serializable} (kế thừa từ {@link DefaultOAuth2User}).
 */
public class AppOAuth2User extends DefaultOAuth2User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;

    public AppOAuth2User(Collection<? extends GrantedAuthority> authorities, Map<String, Object> attributes,
                         String nameAttributeKey, Long userId) {
        super(authorities, attributes, nameAttributeKey);
        Assert.notNull(userId, "userId cannot be null");
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && userId.equals(((AppOAuth2User) obj).userId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + userId.hashCode();
    }
}
