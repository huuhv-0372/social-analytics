package com.huuhv.socialanalytics.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.util.ErrorHandler;

/**
 * Ghi log lỗi của mọi {@code @JmsListener} (gắn vào listener container factory ở {@link JmsConfig}). Được gọi sau khi
 * container đã rollback session — chỉ quyết định cách <b>ghi log</b>, không ảnh hưởng việc broker giao lại / chuyển DLQ.
 * <ul>
 *     <li>Message không chuyển đổi được (JSON hỏng, {@code _type} lạ): không bao giờ xử lý được nhưng vẫn bị giao lại
 *     đủ số lần rồi mới vào DLQ. Mặc định Spring in WARN kèm stack trace ở <b>mỗi</b> lần giao → chỉ ghi 1 dòng. Thông
 *     điệp lấy từ {@link AliasOnlyJsonMessageConverter} — đã bỏ dữ liệu của bên gửi (không có log injection).</li>
 *     <li>Lỗi khác (vd lỗi DB tạm thời): giữ stack trace để debug, message sẽ được giao lại.</li>
 * </ul>
 */
@Slf4j
class JmsListenerErrorHandler implements ErrorHandler {

    @Override
    public void handleError(Throwable error) {
        MessageConversionException conversionError = findConversionError(error);
        if (conversionError != null) {
            log.warn("JMS message could not be converted, broker will redeliver it and then move it to the DLQ: {}",
                    conversionError.getMessage());
            return;
        }
        log.warn("JMS listener failed, broker will redeliver the message", error);
    }

    private static MessageConversionException findConversionError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof MessageConversionException conversion) {
                return conversion;
            }
        }
        return null;
    }
}
