package com.huuhv.socialanalytics.entity.converter;

import com.huuhv.socialanalytics.util.AesGcmCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Mã hoá access token (AES-GCM) khi ghi DB và giải mã khi đọc (OWASP A04).
 * <p>
 * Hibernate tạo converter qua Spring ({@code SpringBeanContainer} do Boot cấu hình) nên inject được bean
 * {@code accessTokenCipher} (khai báo ở {@code config/CryptoConfig}, key từ {@code app.token-encryption.key}).
 * Lấy bean trễ qua {@link ObjectProvider}: test slice {@code @DataJpaTest} không dùng {@code SocialAccount} thì không
 * cần khai báo cipher.
 */
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
        return dbData == null ? null : cipher.getObject().decrypt(dbData);
    }
}
