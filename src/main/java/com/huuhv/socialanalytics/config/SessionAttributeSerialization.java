package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.AppOAuth2User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializingConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * Chuyển attribute session ↔ {@code byte[]} cho bảng {@code SPRING_SESSION_ATTRIBUTES}.
 * <p>
 * Spring Session JDBC mặc định dùng Java serialization không giới hạn: ai ghi được vào bảng session (vd qua một lỗ
 * hổng SQL injection) có thể đưa vào object bất kỳ có trên classpath → gadget chain khi app deserialize (OWASP A08).
 * Ở đây giữ Java serialization (Spring Security chỉ hỗ trợ sẵn cách này cho {@code SecurityContext},
 * {@code OAuth2AuthorizationRequest}, {@code DefaultSavedRequest}) nhưng đọc lại qua {@link ObjectInputFilter}
 * <b>whitelist</b>: chỉ class của JDK cơ bản, Spring Security và principal của app, kèm giới hạn độ sâu / kích thước.
 * Thêm loại attribute session mới (vd flash attribute của {@code RedirectAttributes}) → bổ sung class vào
 * {@link #FILTER_PATTERN} và test round-trip.
 * <p>
 * Không đọc được attribute (class bị lọc, class đã đổi/xoá sau khi deploy bản mới, dữ liệu hỏng) → trả {@code null}
 * + log warn: attribute coi như không có (vd mất {@code SecurityContext} → user phải đăng nhập lại) — fail closed,
 * không trả 500 cho mọi request tới khi session hết hạn.
 */
@Slf4j
final class SessionAttributeSerialization {

    static final String FILTER_PATTERN = String.join(";",
            "maxdepth=32",
            "maxrefs=10000",
            "maxarray=10000",
            "maxbytes=1048576",
            // Collection, String, số (thuộc tính JSON từ provider có thể là BigInteger/BigDecimal), Instant (java.time.Ser)
            "java.lang.*",
            "java.util.*",
            "java.math.*",
            "java.time.*",
            // SecurityContextImpl, OAuth2AuthenticationToken, DefaultOAuth2User, authority, OAuth2AuthorizationRequest,
            // DefaultSavedRequest, WebAuthenticationDetails...
            "org.springframework.security.**",
            AppOAuth2User.class.getName(),
            "!*");

    static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(FILTER_PATTERN);

    private SessionAttributeSerialization() {
    }

    static GenericConversionService conversionService(ClassLoader classLoader) {
        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversionService.addConverter(byte[].class, Object.class, new FilteredDeserializingConverter(classLoader));
        return conversionService;
    }

    private record FilteredDeserializingConverter(ClassLoader classLoader) implements Converter<byte[], Object> {

        @Override
        public Object convert(byte[] source) {
            try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(source), classLoader)) {
                in.setObjectInputFilter(FILTER);
                return in.readObject();
            } catch (IOException | ClassNotFoundException | RuntimeException e) {
                // Thông điệp chỉ chứa tên class / trạng thái filter — không chứa dữ liệu session
                log.warn("Discarding session attribute that cannot be deserialized: {}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                return null;
            }
        }
    }
}
