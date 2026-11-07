package com.huuhv.socialanalytics.client.social;

import java.util.Objects;

/**
 * Số liệu 1 bài viết do {@link SocialPlatformClient} trả về — dữ liệu trung lập, không gắn Entity. Không tự validate
 * giá trị (dữ liệu từ bên ngoài): caller kiểm tra trước khi lưu snapshot.
 *
 * @param followers số người theo dõi tác giả bài viết tại thời điểm lấy số liệu
 */
public record PostMetrics(String externalId, long likes, long shares, long comments, long followers) {

    public PostMetrics {
        Objects.requireNonNull(externalId, "externalId");
    }
}
