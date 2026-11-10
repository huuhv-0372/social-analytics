package com.huuhv.socialanalytics.config;

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

    @Override
    protected void setTypeIdOnMessage(Object object, Message message) throws JMSException {
        if (!registeredTypes.contains(object.getClass())) {
            throw new MessageConversionException(
                    "No JMS type alias registered for " + object.getClass().getName() + " (see MessageTypes)");
        }
        super.setTypeIdOnMessage(object, message);
    }
}
