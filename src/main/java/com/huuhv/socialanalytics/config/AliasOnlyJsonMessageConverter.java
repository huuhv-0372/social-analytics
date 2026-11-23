package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.util.LogSanitizer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageType;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

/**
 * Converter JSON cho JMS (OWASP A08): message luôn là {@code TextMessage} chứa JSON (không {@code ObjectMessage} /
 * Java deserialization), kiểu của payload chỉ truyền qua alias cố định trong property {@value #TYPE_ID_PROPERTY}.
 * <ul>
 *     <li>Nhận: alias không có trong bảng → {@link MessageConversionException}. Converter gốc của Spring sẽ nạp class
 *     theo tên lấy từ message ({@code Class.forName}) khi không có trusted package — ở đây danh sách trusted package
 *     rỗng nên không bao giờ nạp class theo tên.</li>
 *     <li>Gửi: class chưa đăng ký alias → {@link MessageConversionException} ngay ở phía gửi (thay vì để converter gốc
 *     ghi tên class đầy đủ vào message rồi phía nhận mới từ chối).</li>
 * </ul>
 */
public class AliasOnlyJsonMessageConverter extends JacksonJsonMessageConverter {

    public static final String TYPE_ID_PROPERTY = "_type";

    private static final int MAX_MESSAGE_ID_LENGTH = 100;

    private final Set<Class<?>> registeredTypes;

    public AliasOnlyJsonMessageConverter(JsonMapper jsonMapper, Map<String, Class<?>> typeIdMappings) {
        super(jsonMapper);
        setTargetType(MessageType.TEXT);
        setTypeIdPropertyName(TYPE_ID_PROPERTY);
        setTypeIdMappings(typeIdMappings);
        // Mảng rỗng (khác null = tin mọi package): typeId không khớp alias nào → từ chối, không Class.forName
        setTrustedPackages();
        this.registeredTypes = Set.copyOf(typeIdMappings.values());
    }

    /**
     * Lỗi chuyển đổi được ném lại với thông điệp <b>không chứa dữ liệu của message</b> (OWASP A05 — log injection).
     * <p>
     * Thông điệp gốc của Spring/Jackson nối nguyên giá trị do bên gửi đặt (vd {@code The class '<_type>' is not in the
     * trusted packages}, đoạn JSON lỗi) — kể cả CR/LF. Listener container in exception này kèm stack trace ở mỗi lần
     * giao lại, mà {@code %replace} trong {@code LOG_PATTERN} chỉ lọc {@code %msg}, không lọc {@code %ex} → bên gửi
     * tạo được dòng log giả. Vì vậy bỏ exception gốc (cả cause, vì stack trace in cả thông điệp của cause), chỉ giữ tên
     * loại lỗi + {@code JMSMessageID} đã lọc để đối chiếu với log broker.
     */
    @Override
    public Object fromMessage(Message message) throws JMSException, MessageConversionException {
        try {
            return super.fromMessage(message);
        } catch (RuntimeException e) {
            throw new MessageConversionException("Cannot convert JMS message " + safeMessageId(message)
                    + " (" + e.getClass().getSimpleName() + "; details omitted: they may contain sender data)");
        }
    }

    private static String safeMessageId(Message message) {
        try {
            String id = message.getJMSMessageID();
            if (id == null) {
                return "<no id>";
            }
            // Do client phía gửi đặt → không tin cậy: bỏ CR/LF, giới hạn độ dài
            String clean = LogSanitizer.stripLineBreaks(id);
            return clean.length() <= MAX_MESSAGE_ID_LENGTH ? clean : clean.substring(0, MAX_MESSAGE_ID_LENGTH) + "...";
        } catch (JMSException e) {
            return "<unknown id>";
        }
    }

    @Override
    protected void setTypeIdOnMessage(Object object, Message message) throws JMSException {
        if (!registeredTypes.contains(object.getClass())) {
            throw new MessageConversionException(
                    "No JMS type alias registered for " + object.getClass().getName() + " (see MessageTypes)");
        }
        super.setTypeIdOnMessage(object, message);
    }
}
