package com.huuhv.socialanalytics.entity.converter;

import com.huuhv.socialanalytics.util.AesGcmCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Mã hoá access token (AES-GCM) khi ghi DB và giải mã khi đọc (OWASP A04).
 * <p>
 * Hibernate tạo converter qua Spring ({@code SpringBeanContainer} do Boot cấu hình) nên inject được bean
 * {@code accessTokenCipher} (khai báo ở {@code config/CryptoConfig}, key từ {@code app.token-encryption.key}).
 * Lấy bean trễ qua {@link ObjectProvider}: test slice {@code @DataJpaTest} không dùng {@code SocialAccount} thì không
 * cần khai báo cipher.
 * <p>
 * Giải mã thất bại (đã đổi {@code TOKEN_ENCRYPTION_KEY}, dữ liệu hỏng) → coi như <b>không có token</b> ({@code null})
 * thay vì ném lỗi: nếu ném, mọi lần nạp {@code SocialAccount} (kể cả lúc đăng nhập lại để cấp token mới) đều thất bại
 * và user bị khoá vĩnh viễn. {@code null} vẫn là fail closed (OWASP A10): không có token thì không gọi được API mạng
 * xã hội, user đăng nhập lại sẽ ghi đè token mới.
 */
@Slf4j
@Converter
public class AccessTokenConverter implements AttributeConverter<String, String> {

    private final ObjectProvider<AesGcmCipher> cipher;

    public AccessTokenConverter(ObjectProvider<AesGcmCipher> cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.getObject().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return cipher.getObject().decrypt(dbData);
        } catch (IllegalStateException e) {
            // Không log dbData (ciphertext) hay exception (không cần stack trace, thông điệp đã đủ phân loại)
            log.warn("Stored access token cannot be decrypted ({}), treating it as absent", e.getMessage());
            return null;
        }
    }
}
