package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.util.AesGcmCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cipher mã hoá access token lưu DB ({@code entity/converter/AccessTokenConverter}).
 * Key lấy từ biến môi trường {@code TOKEN_ENCRYPTION_KEY} qua {@code app.token-encryption.key} — không hardcode.
 */
// @EnableConfigurationProperties: test slice (@DataJpaTest) @Import class này không có @ConfigurationPropertiesScan
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class CryptoConfig {

    @Bean
    AesGcmCipher accessTokenCipher(AppProperties appProperties) {
        return new AesGcmCipher(appProperties.tokenEncryption().keyBytes());
    }
}
