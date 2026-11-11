package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.ImportCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publish {@link ImportCompletedMessage} lên queue {@value QueueNames#IMPORT_COMPLETED} <b>sau khi</b> transaction
 * import commit ({@code AFTER_COMMIT}): listener không bao giờ đọc phải dữ liệu chưa commit, import bị rollback thì
 * không có message.
 * <p>
 * Chạy đồng bộ trên thread của request import, sau commit; thời gian chờ broker bị chặn bởi {@code callTimeout} trong
 * {@code spring.artemis.broker-url}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportCompletedProducer {

    static final String MDC_IMPORT_ID = "importId";

    private final JmsTemplate jmsTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImportCompleted(ImportCompletedEvent event) {
        // putCloseable: chỉ gỡ khoá importId, giữ MDC khác của thread request
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_IMPORT_ID, String.valueOf(event.importId()))) {
            send(event);
        }
    }

    private void send(ImportCompletedEvent event) {
        try {
            jmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED, ImportCompletedMessage.from(event));
            log.info("Published {} for import {}", QueueNames.IMPORT_COMPLETED, event.importId());
        } catch (RuntimeException e) {
            // Dữ liệu import đã commit — ném lỗi lúc này chỉ làm request trả 500 dù import thành công. Broker lỗi
            // (JmsException) hay lỗi bất ngờ khác đều được xử lý như nhau: log kèm importId, lịch sử import giữ
            // trạng thái PENDING để truy vết (chấp nhận mất message, chưa có outbox / gửi lại).
            log.error("Failed to publish {} for import {}; import history stays PENDING",
                    QueueNames.IMPORT_COMPLETED, event.importId(), e);
        }
    }
}
