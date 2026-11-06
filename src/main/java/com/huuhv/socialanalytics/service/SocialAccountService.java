package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lưu kết quả đăng nhập OAuth2: upsert {@link SocialAccount} theo {@code (provider, providerUserId)} cùng
 * {@link User} sở hữu, kèm access token (mã hoá khi ghi DB) và thời điểm hết hạn.
 * <ul>
 *     <li>Tài khoản mới → tạo user mới. <b>Không</b> tự liên kết với user có sẵn theo email: email do provider trả về
 *     không đủ tin cậy để gộp tài khoản (chiếm tài khoản — OWASP A07).</li>
 *     <li>Tài khoản đã có → cập nhật token, hạn token, tên hiển thị.</li>
 * </ul>
 * Thông tin từ provider là dữ liệu không tin cậy (OWASP A08): chuẩn hoá trước khi lưu. Không log token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialAccountService {

    static final int DISPLAY_NAME_MAX_LENGTH = 100;
    static final int EMAIL_MAX_LENGTH = 255;
    static final int PROVIDER_USER_ID_MAX_LENGTH = 100;

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * @return user sở hữu tài khoản
     * @throws IllegalArgumentException khi id phía provider rỗng hoặc dài quá cột DB
     * @throws org.springframework.dao.DataIntegrityViolationException khi lần đăng nhập song song vừa tạo cùng tài
     *                                                                  khoản/email — caller gọi lại để cập nhật
     */
    @Transactional
    public User recordLogin(Provider provider, OAuth2User oauth2User, OAuth2AccessToken accessToken) {
        String providerUserId = oauth2User.getName();
        if (providerUserId == null || providerUserId.isBlank() || providerUserId.length() > PROVIDER_USER_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid provider user id");
        }
        String displayName = displayName(provider, oauth2User);

        Optional<SocialAccount> existing =
                socialAccountRepository.findWithUserByProviderAndProviderUserId(provider, providerUserId);
        SocialAccount account;
        if (existing.isPresent()) {
            account = existing.get();
            account.getUser().setDisplayName(displayName);
        } else {
            User user = userRepository.saveAndFlush(User.builder()
                    .email(unusedEmail(oauth2User).orElse(null))
                    .displayName(displayName)
                    .build());
            account = SocialAccount.builder()
                    .user(user)
                    .provider(provider)
                    .providerUserId(providerUserId)
                    .build();
        }
        account.setAccessToken(accessToken.getTokenValue());
        account.setTokenExpiresAt(toLocalDateTime(accessToken.getExpiresAt()));
        // Flush ngay để vi phạm unique (đăng nhập song song) ném ra tại đây, trong phạm vi transaction này
        socialAccountRepository.saveAndFlush(account);

        if (existing.isEmpty()) {
            log.info("Created user {} for new {} account", account.getUser().getId(), provider);
        }
        return account.getUser();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }

    /** Tên hiển thị: {@code name} → {@code username} (X) → tên mặc định theo provider; cắt theo độ dài cột. */
    static String displayName(Provider provider, OAuth2User oauth2User) {
        return sanitize(oauth2User.getAttribute("name"))
                .or(() -> sanitize(oauth2User.getAttribute("username")))
                .map(name -> truncate(name, DISPLAY_NAME_MAX_LENGTH))
                .orElse(provider == Provider.TWITTER ? "X user" : "Facebook user");
    }

    /** Email (chỉ Facebook có) dùng cho user mới; đã thuộc user khác thì bỏ qua — không gộp tài khoản theo email. */
    private Optional<String> unusedEmail(OAuth2User oauth2User) {
        return sanitize(oauth2User.getAttribute("email"))
                .filter(email -> email.length() <= EMAIL_MAX_LENGTH && SIMPLE_EMAIL.matcher(email).matches())
                .filter(email -> !userRepository.existsByEmail(email));
    }

    private static Optional<String> sanitize(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        String cleaned = CONTROL_CHARS.matcher(text).replaceAll("").strip();
        return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    /** Cắt theo code point (VARCHAR của MySQL đếm ký tự) để không tách đôi cặp surrogate (emoji). */
    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
