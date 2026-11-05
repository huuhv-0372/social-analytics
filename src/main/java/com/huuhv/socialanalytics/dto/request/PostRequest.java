package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Dữ liệu tạo / cập nhật (thay thế toàn bộ) bài viết. Owner không nằm trong request — lấy từ user đang đăng nhập.
 */
public record PostRequest(
        @NotNull Provider provider,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_\\-]+", message = "must contain only letters, digits, '_' or '-'")
        String externalId,
        @NotBlank @Size(max = 500) String url,
        @Size(max = 5000) String content,
        @PastOrPresent LocalDateTime publishedAt) {
}
