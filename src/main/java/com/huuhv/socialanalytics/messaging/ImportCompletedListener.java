package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportStatisticsService;
import com.huuhv.socialanalytics.service.ImportStatisticsService.ApplyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.PessimisticLockingFailureException;
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
                // Payload là dữ liệu không tin cậy (OWASP A08). Không log nội dung message. Không có importId thì
                // không biết là lần import nào → không đánh dấu được, retry cũng vô ích
                log.warn("Discarding {} message without a valid importId", QueueNames.IMPORT_COMPLETED);
                return;
            }
            ApplyResult result = apply(message);
            switch (result) {
                // Owner thật (trong DB) được ImportStatisticsService log — userId của payload không đáng tin
                case APPLIED -> log.debug("Import statistics updated");
                case ALREADY_PROCESSED -> log.info("Import already processed, duplicate message ignored");
                case NOT_FOUND -> log.warn("No import history for this message, ignored");
            }
        } finally {
            // Thread của listener container dùng lại cho message sau → không để sót importId
            MDC.clear();
        }
    }

    /**
     * Tranh chấp khoá (deadlock / lock wait timeout) — vd 2 lần import đầu tiên của cùng user mới được xử lý song song,
     * cả 2 cùng {@code INSERT ... ON DUPLICATE KEY UPDATE} dòng thống kê chưa tồn tại — thử lại ngay 1 lần trong
     * transaction mới (transaction cũ đã rollback, xử lý lại idempotent) thay vì tốn 1 lần giao của broker. Lỗi DB khác
     * hoặc lần thử lại vẫn lỗi → exception thoát ra, broker giao lại theo address setting.
     */
    private ApplyResult apply(ImportCompletedMessage message) {
        try {
            return importStatisticsService.applyImport(message.importId(), message.userId());
        } catch (PessimisticLockingFailureException e) {
            log.debug("Lock conflict while updating import statistics ({}), retrying", e.getClass().getSimpleName());
            return importStatisticsService.applyImport(message.importId(), message.userId());
        }
    }

    /**
     * Chỉ cần {@code importId} hợp lệ: số liệu và owner được Service đọc lại từ {@code import_histories}, không lấy từ
     * payload. {@code userId} thiếu/sai (producer phiên bản khác, message gửi lại bằng tay) vẫn xử lý được — Service
     * chỉ log cảnh báo lệch owner. Nếu bỏ message ở đây, import sẽ kẹt {@code PENDING} dù DB đủ dữ liệu để cộng đúng.
     */
    private static boolean isValid(ImportCompletedMessage message) {
        return message.importId() != null && message.importId() > 0;
    }
}
