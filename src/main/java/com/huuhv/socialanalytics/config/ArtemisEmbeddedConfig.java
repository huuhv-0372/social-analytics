package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.springframework.boot.artemis.autoconfigure.ArtemisConfigurationCustomizer;
import org.springframework.boot.artemis.autoconfigure.ArtemisMode;
import org.springframework.boot.artemis.autoconfigure.ArtemisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Cấu hình broker Artemis <b>embedded</b> (dev/test) từ cùng file "broker properties" mà broker riêng của docker
 * compose nạp qua {@code artemis run --properties} ({@code classpath:artemis/broker.properties}) — retry + DLQ của
 * queue {@code import-completed} chỉ khai báo ở 1 nơi.
 * <p>
 * Boot gọi {@link ArtemisConfigurationCustomizer} mỗi khi tạo broker trong JVM. Lưu ý: Boot tạo broker này dựa trên
 * {@code spring.artemis.embedded.enabled} (mặc định {@code true}) và việc có {@code artemis-jakarta-server} trên
 * classpath — <b>không</b> xét {@code spring.artemis.mode}. Vì vậy {@code application.yml} (production, native) đặt
 * {@code embedded.enabled=false}; dev/test bật lại. Customizer kiểm tra thêm: broker embedded chạy trong khi
 * {@code mode=native} (vd chỉ đổi {@code ARTEMIS_MODE} mà quên {@code ARTEMIS_EMBEDDED_ENABLED}) → dừng khởi động với
 * thông báo rõ, thay vì lặng lẽ chạy thêm 1 broker không ai dùng.
 * <p>
 * Nạp lần lượt các file trong {@code app.jms.embedded-broker-properties}, file sau ghi đè khoá trùng của file trước
 * (test thêm file rút ngắn độ trễ giao lại). Parser của Artemis không ném lỗi khi gặp khoá sai mà chỉ ghi vào
 * {@link ConfigurationImpl#getStatus()} → kiểm tra status và dừng khởi động nếu có lỗi (fail fast), không để retry/DLQ
 * lặng lẽ mất tác dụng.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class ArtemisEmbeddedConfig {

    @Bean
    ArtemisConfigurationCustomizer brokerPropertiesCustomizer(AppProperties appProperties,
                                                              ArtemisProperties artemisProperties,
                                                              ResourceLoader resourceLoader) {
        List<String> locations = appProperties.jms().embeddedBrokerProperties();
        return configuration -> {
            requireEmbeddedMode(artemisProperties.getMode());
            applyBrokerProperties(configuration, loadProperties(locations, resourceLoader));
        };
    }

    /**
     * Broker embedded chỉ hợp lệ khi app thực sự dùng nó. {@code mode} rỗng = Boot tự chọn (có broker embedded thì
     * dùng embedded) → hợp lệ.
     */
    static void requireEmbeddedMode(ArtemisMode mode) {
        if (mode == ArtemisMode.NATIVE) {
            throw new IllegalStateException("Embedded Artemis broker is enabled but spring.artemis.mode=native: set"
                    + " spring.artemis.embedded.enabled=false (dev profile: ARTEMIS_EMBEDDED_ENABLED=false)");
        }
    }

    /** Gộp các file theo thứ tự — khoá trùng lấy giá trị của file sau. File không tồn tại → lỗi. */
    static Properties loadProperties(List<String> locations, ResourceLoader resourceLoader) {
        Properties properties = new Properties();
        for (String location : locations) {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                throw new IllegalStateException("Artemis broker properties not found: " + location);
            }
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read Artemis broker properties: " + location, e);
            }
        }
        return properties;
    }

    static void applyBrokerProperties(Configuration configuration, Properties properties) {
        // parsePrefixedProperties chỉ có trên ConfigurationImpl (Boot luôn tạo ConfigurationImpl cho broker embedded)
        if (!(configuration instanceof ConfigurationImpl impl)) {
            throw new IllegalStateException("Unsupported Artemis configuration type: " + configuration.getClass());
        }
        if (properties.isEmpty()) {
            // File chỉ có comment → broker chạy không có retry/DLQ
            throw new IllegalStateException("Artemis broker properties contain no keys");
        }
        try {
            impl.parsePrefixedProperties(properties, null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot apply Artemis broker properties", e);
        }
        List<String> errors = propertyErrors(impl.getStatus());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid Artemis broker properties: " + errors);
        }
    }

    /**
     * Lỗi parse trong status JSON của Artemis: {@code {"properties":{"<nguồn>":{"errors":[{"value","reason"}]}}}}.
     * {@code value} là dòng {@code khoá=giá trị} của file cấu hình (không chứa secret).
     * <p>
     * Định dạng này là chi tiết nội bộ của Artemis: sau khi parse, Artemis luôn ghi ít nhất 1 nguồn dưới
     * {@code properties} (kể cả khi không lỗi) → không thấy thì coi như định dạng đã đổi (vd sau khi nâng version) và
     * ném lỗi, thay vì kết luận "không có lỗi" khiến khoá sai lại bị bỏ qua lặng lẽ (fail closed).
     */
    static List<String> propertyErrors(String status) {
        List<String> errors = new ArrayList<>();
        JsonNode sources = JsonMapper.shared().readTree(status == null ? "{}" : status).path("properties");
        if (!sources.isObject() || sources.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot verify Artemis broker properties: unrecognised configuration status format");
        }
        for (JsonNode source : sources) {
            for (JsonNode error : source.path("errors")) {
                errors.add(error.path("value").asString() + " (" + error.path("reason").asString() + ")");
            }
        }
        return errors;
    }
}
