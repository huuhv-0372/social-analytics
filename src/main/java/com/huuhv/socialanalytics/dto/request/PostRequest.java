package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Dữ liệu tạo / cập nhật (thay thế toàn bộ) bài viết. Owner không nằm trong request — lấy từ user đang đăng nhập.
 * <p>
 * {@code publishedAt} được cắt về độ chính xác giây (REST và import Excel đều đi qua constructor này): ô ngày giờ của
 * Excel chỉ giữ tới mili giây, nếu lưu micro giây thì export → import lại sẽ ghi đè giá trị dù người dùng không sửa ô.
 */
public record PostRequest(
        @NotNull Provider provider,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_\\-]+", message = "must contain only letters, digits, '_' or '-'")
        String externalId,
        @NotBlank @Size(max = 500) String url,
        @Size(max = 5000) String content,
        @PastOrPresent LocalDateTime publishedAt) {

    public PostRequest {
        if (publishedAt != null) {
            publishedAt = publishedAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
