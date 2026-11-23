package com.huuhv.socialanalytics.service;

import java.time.Instant;

/**
 * Sự kiện trong JVM (Spring {@code ApplicationEvent}): 1 lần import đã được ghi trong transaction hiện tại.
 * {@code messaging/ImportCompletedProducer} nhận sự kiện <b>sau khi transaction commit</b> rồi mới publish message
 * JMS — rollback thì không có message nào được gửi.
 *
 * @param importId    id của {@code import_histories}
 * @param successRows số dòng đã lưu (tạo mới + cập nhật)
 * @param occurredAt  thời điểm ghi lịch sử import
 */
public record ImportCompletedEvent(long importId, long userId, int totalRows, int successRows, int failedRows,
                                   Instant occurredAt) {
}
