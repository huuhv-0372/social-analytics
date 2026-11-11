package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.service.ImportStatisticsService.ApplyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Nhận {@link ImportCompletedMessage} và cập nhật thống kê import tổng hợp (bất đồng bộ so với request import).
 * <p>
 * Session transacted ({@code spring.jms.listener.session.transacted}): exception thoát khỏi method → broker giao lại
 * message. Vì vậy chỉ để exception thoát ra khi xử lý lại có thể thành công (lỗi DB tạm thời); message sai định dạng
 * nghiệp vụ thì log rồi bỏ (retry vô ích). Xử lý idempotent theo {@code importId} ở
 * {@link ImportStatisticsService#applyImport} — nhận lại message không cộng trùng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportCompletedListener {

    // Cùng khoá MDC với producer (logback LOG_PATTERN in %X{importId})
    static final String MDC_IMPORT_ID = ImportCompletedProducer.MDC_IMPORT_ID;

    private final ImportStatisticsService importStatisticsService;

    @JmsListener(destination = QueueNames.IMPORT_COMPLETED)
    public void onImportCompleted(ImportCompletedMessage message) {
        try {
            if (message.importId() != null) {
                MDC.put(MDC_IMPORT_ID, String.valueOf(message.importId()));
            }
            if (!isValid(message)) {
                // Payload là dữ liệu không tin cậy (OWASP A08). Không log nội dung message
                log.warn("Discarding invalid {} message", QueueNames.IMPORT_COMPLETED);
                return;
            }
            ApplyResult result = importStatisticsService.applyImport(message.importId(), message.userId());
            switch (result) {
                case APPLIED -> log.info("Import statistics updated for user {}", message.userId());
                case ALREADY_PROCESSED -> log.info("Import already processed, duplicate message ignored");
                case NOT_FOUND -> log.warn("No import history for this message, ignored");
            }
        } finally {
            // Thread của listener container dùng lại cho message sau → không để sót importId
            MDC.clear();
        }
    }

    private static boolean isValid(ImportCompletedMessage message) {
        return message.importId() != null && message.importId() > 0
                && message.userId() != null && message.userId() > 0;
    }
}
