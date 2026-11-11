package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportHistoryService;
import com.huuhv.socialanalytics.util.LogSanitizer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Nhận message {@code IMPORT_COMPLETED} đã giao lỗi đủ số lần tối đa và bị broker chuyển sang
 * {@value QueueNames#IMPORT_COMPLETED_DLQ}: ghi log cảnh báo (OWASP A09) và đánh dấu lịch sử import
 * {@code DEAD_LETTERED}.
 * <p>
 * Listener <b>lấy message khỏi DLQ</b> (ack khi method kết thúc bình thường) — dấu vết lâu dài là log + trạng thái
 * {@code DEAD_LETTERED} trong {@code import_histories}, không phải message trên broker. Payload chỉ gồm id/số lượng nên
 * dựng lại được từ DB: sau khi khắc phục lỗi, gửi lại {@code IMPORT_COMPLETED} cho các import {@code DEAD_LETTERED}
 * thì listener chính vẫn cộng (đúng 1 lần). Lỗi DB khi đánh dấu → exception thoát ra → broker giao lại message từ DLQ
 * (theo address setting mặc định {@code #}), không mất cảnh báo.
 * <p>
 * Nhận {@link Message} thô thay vì {@link ImportCompletedMessage}: message có thể vào DLQ chính vì payload không đọc
 * được — chuyển đổi ở tham số method sẽ lỗi lại ở đây. Payload không đọc được chỉ được log, không ném exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportCompletedDlqListener {

    /** Property Artemis gắn khi chuyển message sang dead-letter address: address gốc của message. */
    static final String ORIGINAL_ADDRESS_PROPERTY = "_AMQ_ORIG_ADDRESS";

    private final MessageConverter messageConverter;
    private final ImportHistoryService importHistoryService;

    @JmsListener(destination = QueueNames.IMPORT_COMPLETED_DLQ)
    public void onDeadLetter(Message message) {
        try {
            Long importId = readImportId(message);
            if (importId != null) {
                MDC.put(ImportCompletedListener.MDC_IMPORT_ID, String.valueOf(importId));
            }
            // Cảnh báo vận hành: thống kê của lần import này chưa được cập nhật. Không log payload
            log.warn("Message from {} moved to {} after max delivery attempts (importId={})",
                    originalAddress(message), QueueNames.IMPORT_COMPLETED_DLQ, importId);
            if (importId == null) {
                return;
            }
            if (importHistoryService.markDeadLettered(importId)) {
                log.warn("Import marked DEAD_LETTERED; statistics do not include it until the message is resent");
            } else {
                log.info("Import is not PENDING (already processed or unknown), status unchanged");
            }
        } finally {
            MDC.clear();
        }
    }

    /** {@code importId} của payload, hoặc {@code null} khi payload không đọc được / không hợp lệ. */
    private Long readImportId(Message message) {
        try {
            if (messageConverter.fromMessage(message) instanceof ImportCompletedMessage payload
                    && payload.importId() != null && payload.importId() > 0) {
                return payload.importId();
            }
            log.warn("Dead-lettered message has no valid importId");
        } catch (JMSException | RuntimeException e) {
            // Thông điệp lỗi có thể chứa dữ liệu của message (vd giá trị _type) → chỉ log loại lỗi
            log.warn("Dead-lettered message is unreadable ({})", e.getClass().getSimpleName());
        }
        return null;
    }

    private static String originalAddress(Message message) {
        try {
            String address = message.getStringProperty(ORIGINAL_ADDRESS_PROPERTY);
            return address == null ? "unknown address" : LogSanitizer.stripLineBreaks(address);
        } catch (JMSException e) {
            return "unknown address";
        }
    }
}
