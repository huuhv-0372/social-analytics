package com.huuhv.socialanalytics.util;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hoá chuỗi bằng AES-256-GCM của JCA (không tự viết thuật toán — OWASP A04).
 * <p>
 * Mỗi lần mã hoá sinh IV 12 byte ngẫu nhiên bằng {@link SecureRandom}; kết quả là Base64 của
 * {@code IV || ciphertext || tag (128 bit)}. GCM xác thực dữ liệu nên ciphertext bị sửa hoặc giải mã bằng sai key
 * đều thất bại. Thread-safe: {@link Cipher} tạo mới mỗi lần gọi, {@link SecureRandom} dùng chung được.
 */
public final class AesGcmCipher {

    public static final int KEY_LENGTH_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCipher(byte[] key) {
        if (key == null || key.length != KEY_LENGTH_BYTES) {
            // Không đưa độ dài/giá trị key vào thông điệp lỗi
            throw new IllegalArgumentException("AES-GCM key must be 256 bits");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * @throws IllegalStateException khi dữ liệu sai định dạng, bị sửa, hoặc mã hoá bằng key khác — thông điệp không
     *                               chứa dữ liệu đầu vào
     */
    public String decrypt(String encoded) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed: invalid encoding");
        }
        if (data.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / Byte.SIZE) {
            throw new IllegalStateException("Decryption failed: invalid length");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, data, 0, IV_LENGTH_BYTES));
            byte[] plaintext = cipher.doFinal(data, IV_LENGTH_BYTES, data.length - IV_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Decryption failed: authentication tag mismatch");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
