package com.huuhv.socialanalytics.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static final byte[] KEY = filled(32, (byte) 1);

    private final AesGcmCipher cipher = new AesGcmCipher(KEY);

    @Test
    void roundTripRestoresPlaintext() {
        String token = "EAAB-access-token-ưu-tiên-😀";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).doesNotContain(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void sameInputGivesDifferentCiphertextBecauseIvIsRandom() {
        String first = cipher.encrypt("same-token");
        String second = cipher.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        // 12 byte IV đầu khác nhau
        assertThat(Arrays.copyOf(Base64.getDecoder().decode(first), 12))
                .isNotEqualTo(Arrays.copyOf(Base64.getDecoder().decode(second), 12));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] data = Base64.getDecoder().decode(cipher.encrypt("token"));
        data[data.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(data)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciphertextFromAnotherKeyIsRejected() {
        String encrypted = new AesGcmCipher(filled(32, (byte) 2)).encrypt("token");

        assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedInputIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> cipher.decrypt("not base64 !!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not base64");
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[10])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe256Bits() {
        assertThatThrownBy(() -> new AesGcmCipher(filled(16, (byte) 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmCipher(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
