package com.huuhv.socialanalytics.entity.converter;

import com.huuhv.socialanalytics.util.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenConverterTest {

    private static final AesGcmCipher KEY_A = new AesGcmCipher(filled((byte) 1));
    private static final AesGcmCipher KEY_B = new AesGcmCipher(filled((byte) 2));

    private final AccessTokenConverter converter = converterWith(KEY_A);

    @Test
    void roundTripRestoresToken() {
        String stored = converter.convertToDatabaseColumn("access-token");

        assertThat(stored).isNotBlank().doesNotContain("access-token");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("access-token");
    }

    @Test
    void nullPassesThroughBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void tokenWrittenWithOldKeyIsTreatedAsAbsentAfterKeyRotation() {
        String storedWithKeyA = converter.convertToDatabaseColumn("access-token");

        // Không ném lỗi (nếu ném, nạp SocialAccount thất bại → user không đăng nhập lại được)
        assertThat(converterWith(KEY_B).convertToEntityAttribute(storedWithKeyA)).isNull();
    }

    @Test
    void tamperedCiphertextIsTreatedAsAbsent() {
        byte[] data = Base64.getDecoder().decode(converter.convertToDatabaseColumn("access-token"));
        data[data.length - 1] ^= 1;

        assertThat(converter.convertToEntityAttribute(Base64.getEncoder().encodeToString(data))).isNull();
    }

    @Test
    void malformedColumnValueIsTreatedAsAbsent() {
        assertThat(converter.convertToEntityAttribute("not-base64-%%%")).isNull();
        assertThat(converter.convertToEntityAttribute("")).isNull();
        // Token lưu dạng rõ (vd dữ liệu nhập tay) không bao giờ được trả ra như token hợp lệ
        assertThat(converter.convertToEntityAttribute("EAABplaintexttoken")).isNull();
    }

    private static AccessTokenConverter converterWith(AesGcmCipher cipher) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("accessTokenCipher", cipher);
        ObjectProvider<AesGcmCipher> provider = beanFactory.getBeanProvider(AesGcmCipher.class);
        return new AccessTokenConverter(provider);
    }

    private static byte[] filled(byte value) {
        byte[] key = new byte[AesGcmCipher.KEY_LENGTH_BYTES];
        Arrays.fill(key, value);
        return key;
    }
}
