package com.huuhv.socialanalytics.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.util.Assert;

import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Principal OAuth2 của app: thuộc tính từ provider + id nội bộ và tên hiển thị của {@code User} đã lưu lúc đăng nhập
 * ({@code service.CustomOAuth2UserService}). {@code service.CurrentUserService} lấy owner từ {@link #getUserId()};
 * layout lấy tên từ {@link #getDisplayName()} — cùng giá trị đã chuẩn hoá và lưu ở {@code users.display_name}, không
 * tính lại.
 * <p>
 * Đặt ở {@code security} (không ở {@code service}) để {@link SessionAttributeSerialization} whitelist được class này
 * mà không phụ thuộc ngược vào {@code service}. Đổi tên/package class = session cũ không đọc được → user phải đăng
 * nhập lại (fail closed, không 500).
 * <p>
 * {@code userId} do server gán sau khi lưu DB, nằm trong session phía server — client không sửa được (OWASP A01).
 * Lưu trong session nên phải {@link java.io.Serializable} (kế thừa từ {@link DefaultOAuth2User}).
 */
public class AppOAuth2User extends DefaultOAuth2User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;

    /** Có thể {@code null} với session tạo trước khi principal có trường này. */
    private final String displayName;

    public AppOAuth2User(Collection<? extends GrantedAuthority> authorities, Map<String, Object> attributes,
                         String nameAttributeKey, Long userId, String displayName) {
        super(authorities, attributes, nameAttributeKey);
        Assert.notNull(userId, "userId cannot be null");
        this.userId = userId;
        this.displayName = displayName;
    }

    public Long getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && userId.equals(((AppOAuth2User) obj).userId)
                && Objects.equals(displayName, ((AppOAuth2User) obj).displayName);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * super.hashCode() + userId.hashCode()) + Objects.hashCode(displayName);
    }
}
