package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Dữ liệu tạo / cập nhật (thay thế toàn bộ) bài viết. Owner không nằm trong request — lấy từ user đang đăng nhập.
 * {@code publishedAt} là mốc thời gian tuyệt đối: ISO-8601 có {@code Z} hoặc offset (vd {@code 2026-11-07T01:00:00Z});
 * thiếu múi giờ → 400.
 * <p>
 * {@code publishedAt} được cắt về độ chính xác giây (REST và import Excel đều đi qua constructor này): ô ngày giờ của
 * Excel chỉ giữ tới mili giây, nếu lưu micro giây thì export → import lại sẽ ghi đè giá trị dù người dùng không sửa ô.
 */
public record PostRequest(
        @NotNull Provider provider,
        @NotBlank @Size(max = EXTERNAL_ID_MAX_LENGTH)
        @Pattern(regexp = EXTERNAL_ID_PATTERN, message = EXTERNAL_ID_MESSAGE)
        String externalId,
        @NotBlank @Size(max = 500) String url,
        @Size(max = 5000) String content,
        @PastOrPresent Instant publishedAt) {

    /**
     * Id bài phía provider chỉ gồm ký tự ASCII này: trong tập đó collation {@code utf8mb4_unicode_ci} của cột
     * {@code posts.external_id} chỉ khác so sánh chính xác ở chữ hoa/thường → so khớp trong Java bằng lowercase
     * ({@code PostUpsertService.matchKey}) cho cùng kết quả với DB. Luồng nào tra bài theo externalId do người dùng nhập
     * phải kiểm tra quy tắc này trước ({@link #isValidExternalId}).
     */
    public static final String EXTERNAL_ID_PATTERN = "[A-Za-z0-9_\\-]+";
    public static final int EXTERNAL_ID_MAX_LENGTH = 100;
    public static final String EXTERNAL_ID_MESSAGE = "must contain only letters, digits, '_' or '-'";
    private static final java.util.regex.Pattern EXTERNAL_ID_REGEX =
            java.util.regex.Pattern.compile(EXTERNAL_ID_PATTERN);

    public PostRequest {
        if (publishedAt != null) {
            publishedAt = publishedAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }

    /** Cùng quy tắc với Bean Validation của {@code externalId} (không rỗng, tối đa 100 ký tự, {@link #EXTERNAL_ID_PATTERN}). */
    public static boolean isValidExternalId(String externalId) {
        return externalId != null && externalId.length() <= EXTERNAL_ID_MAX_LENGTH
                && EXTERNAL_ID_REGEX.matcher(externalId).matches();
    }
}
