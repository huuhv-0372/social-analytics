package com.huuhv.socialanalytics.dto.response;

import java.time.LocalDateTime;

/**
 * Projection số liệu của 1 lần import ({@code import_histories}) để listener cộng vào thống kê tổng hợp. Không load
 * Entity.
 *
 * @param successRows tạo mới + cập nhật
 * @param importedAt  {@code created_at} của lịch sử import (UTC)
 */
public record ImportCounts(Long userId, int totalRows, int successRows, int failedRows, LocalDateTime importedAt) {
}
