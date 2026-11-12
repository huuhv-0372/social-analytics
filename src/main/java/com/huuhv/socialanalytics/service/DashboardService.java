package com.huuhv.socialanalytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Dữ liệu hiển thị trên trang {@code /dashboard} của user đang đăng nhập.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CurrentUserService currentUserService;
    private final SocialAccountService socialAccountService;
    private final Clock clock;

    /**
     * "Last updated time": lần job cập nhật metrics thành công gần nhất trong các tài khoản của user hiện tại.
     * {@code last_updated_at} lưu giờ UTC → đổi sang múi giờ của server ({@link Clock#getZone()}).
     *
     * @return rỗng khi job chưa cập nhật tài khoản nào của user
     */
    public Optional<ZonedDateTime> findLastUpdatedAt() {
        Long userId = currentUserService.getCurrentUser().getId();
        return socialAccountService.findLastMetricsUpdatedAt(userId)
                .map(utc -> utc.atZone(ZoneOffset.UTC).withZoneSameInstant(clock.getZone()));
    }
}
