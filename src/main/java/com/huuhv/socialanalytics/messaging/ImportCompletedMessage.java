package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportCompletedEvent;

import java.time.Instant;

/**
 * Payload JSON của message trên queue {@value QueueNames#IMPORT_COMPLETED} (alias {@value MessageTypes#IMPORT_COMPLETED}).
 * Chỉ chứa id và số lượng — không có nội dung bài viết, token hay dữ liệu cá nhân. Đây là "hợp đồng" giữa producer và
 * listener: đổi/xoá field làm message đang nằm trong queue không đọc được, chỉ nên thêm field mới.
 *
 * @param importId    id lịch sử import ({@code import_histories})
 * @param userId      người import
 * @param successRows số dòng đã lưu (tạo mới + cập nhật)
 * @param occurredAt  thời điểm import (UTC, ISO-8601)
 */
public record ImportCompletedMessage(Long importId, Long userId, int totalRows, int successRows, int failedRows,
                                     Instant occurredAt) {

    public static ImportCompletedMessage from(ImportCompletedEvent event) {
        return new ImportCompletedMessage(event.importId(), event.userId(), event.totalRows(), event.successRows(),
                event.failedRows(), event.occurredAt());
    }
}
