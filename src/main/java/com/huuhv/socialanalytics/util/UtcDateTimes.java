package com.huuhv.socialanalytics.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Quy ước thời gian: API trao đổi mốc thời gian bằng {@link Instant} (ISO-8601 có {@code Z}/offset), DB lưu
 * {@link LocalDateTime} ↔ {@code DATETIME(6)} theo giờ <b>UTC</b> — không phụ thuộc múi giờ của JVM hay của client.
 * Giá trị cắt về micro giây cho khớp độ chính xác của {@code DATETIME(6)}.
 */
public final class UtcDateTimes {

    private UtcDateTimes() {
    }

    /** Thời điểm hiện tại theo giờ UTC, dạng lưu DB. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** {@link Instant} → giờ UTC để lưu DB; {@code null} giữ nguyên. */
    public static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** Giờ UTC đọc từ DB → {@link Instant} trả cho client; {@code null} giữ nguyên. */
    public static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
