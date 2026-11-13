package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.util.LogSanitizer;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kiểm tra lúc khởi động (chỉ {@code spring.artemis.mode=native}) rằng broker riêng đã nạp
 * {@code artemis/broker.properties}: address {@value QueueNames#IMPORT_COMPLETED} chuyển message lỗi sang
 * {@value QueueNames#IMPORT_COMPLETED_DLQ}, queue DLQ đã tồn tại và chính DLQ được giao lại vô hạn.
 * <p>
 * Vì sao cần: retry/DLQ cấu hình <b>trên broker</b>, app không tự áp được. Broker production khởi động không có
 * {@code --properties broker.properties} (hoặc file có khoá sai — broker chỉ bỏ qua) thì address kế thừa setting
 * {@code #}: 10 lần giao không delay rồi sang queue {@code DLQ} chung mà {@link ImportCompletedDlqListener} không nghe →
 * import kẹt {@code PENDING}, không có cảnh báo nào. Broker embedded (dev/test) đã được {@code config/ArtemisEmbeddedConfig}
 * kiểm tra nên không cần bean này.
 * <p>
 * Đọc qua management API của Artemis (request/reply trên address {@value #MANAGEMENT_ADDRESS}), tài khoản broker cần
 * quyền {@code manage}. Sai cấu hình → log ERROR (OWASP A09), <b>không</b> chặn khởi động: import vẫn chạy, chỉ thiếu
 * retry/DLQ. Không kiểm tra được (broker chưa sẵn sàng, thiếu quyền) → log WARN.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.artemis.mode", havingValue = "native")
public class BrokerRetrySettingsVerifier {

    static final String MANAGEMENT_ADDRESS = "activemq.management";

    /** Chờ broker trả lời management request — không giữ thread khởi động lâu khi broker không phản hồi. */
    private static final long REPLY_TIMEOUT_MS = 5_000;

    private final JmsTemplate managementTemplate;

    public BrokerRetrySettingsVerifier(ConnectionFactory connectionFactory) {
        // JmsTemplate riêng: JmsTemplate của Boot chờ reply vô hạn và dùng converter JSON của app
        this.managementTemplate = new JmsTemplate(connectionFactory);
        this.managementTemplate.setReceiveTimeout(REPLY_TIMEOUT_MS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        List<String> problems;
        try {
            problems = findProblems();
        } catch (RuntimeException e) {
            log.warn("Could not verify broker retry/DLQ settings for {} ({}); make sure the broker loads"
                    + " artemis/broker.properties", QueueNames.IMPORT_COMPLETED, e.getClass().getSimpleName());
            return;
        }
        if (problems.isEmpty()) {
            log.info("Broker retry/DLQ settings for {} verified", QueueNames.IMPORT_COMPLETED);
        } else {
            log.error("Broker is not configured with artemis/broker.properties ({}): failed {} messages will not be"
                    + " retried/dead-lettered as expected and imports may stay PENDING", problems,
                    QueueNames.IMPORT_COMPLETED);
        }
    }

    /**
     * Các điểm cấu hình broker khác với {@code artemis/broker.properties}; rỗng = đúng.
     *
     * @throws RuntimeException không gọi được management API (broker không phản hồi, thiếu quyền...)
     */
    List<String> findProblems() {
        List<String> problems = new ArrayList<>();
        JsonNode settings = addressSettings(QueueNames.IMPORT_COMPLETED);
        // Artemis cũ (< 2.27) dùng khoá "DLA" thay cho "deadLetterAddress" trong JSON này
        String deadLetterAddress = settings.has("deadLetterAddress")
                ? settings.path("deadLetterAddress").asString(null)
                : settings.path("DLA").asString(null);
        if (!QueueNames.IMPORT_COMPLETED_DLQ.equals(deadLetterAddress)) {
            // Giá trị do broker trả về → lọc CR/LF trước khi đưa vào log
            problems.add(QueueNames.IMPORT_COMPLETED + " deadLetterAddress="
                    + LogSanitizer.stripLineBreaks(String.valueOf(deadLetterAddress)));
        }
        if (!queueNames().contains(QueueNames.IMPORT_COMPLETED_DLQ)) {
            // Dead-letter address không có queue → broker bỏ message
            problems.add("queue " + QueueNames.IMPORT_COMPLETED_DLQ + " does not exist");
        }
        // Không khai báo maxDeliveryAttempts = dùng mặc định 10 của broker
        if (addressSettings(QueueNames.IMPORT_COMPLETED_DLQ).path("maxDeliveryAttempts").asInt(10) != -1) {
            problems.add(QueueNames.IMPORT_COMPLETED_DLQ + " maxDeliveryAttempts is not -1");
        }
        return problems;
    }

    private JsonNode addressSettings(String address) {
        Object result = invoke("getAddressSettingsAsJSON", address);
        return JsonMapper.shared().readTree(String.valueOf(result));
    }

    private List<String> queueNames() {
        Object result = invoke("getQueueNames");
        if (!(result instanceof Object[] names)) {
            throw new IllegalStateException("Unexpected management result type for getQueueNames");
        }
        return Arrays.stream(names).map(String::valueOf).toList();
    }

    private Object invoke(String operation, Object... parameters) {
        Message reply = managementTemplate.sendAndReceive(MANAGEMENT_ADDRESS, session -> {
            Message request = session.createMessage();
            JMSManagementHelper.putOperationInvocation(request, ResourceNames.BROKER, operation, parameters);
            return request;
        });
        if (reply == null) {
            throw new IllegalStateException("No reply from broker management for " + operation);
        }
        try {
            if (!JMSManagementHelper.hasOperationSucceeded(reply)) {
                throw new IllegalStateException("Broker management operation failed: " + operation);
            }
            return JMSManagementHelper.getResult(reply);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read broker management reply for " + operation, e);
        }
    }
}
