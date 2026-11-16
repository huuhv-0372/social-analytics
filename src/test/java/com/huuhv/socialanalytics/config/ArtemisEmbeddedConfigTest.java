package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.messaging.QueueNames;
import com.huuhv.socialanalytics.messaging.TopicNames;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nạp chính file {@code artemis/broker.properties} mà broker riêng của docker compose dùng: tên address/DLQ trong file
 * phải khớp {@link QueueNames} / {@link TopicNames}, không có khoá sai (broker riêng chỉ bỏ qua khoá sai, không báo lỗi).
 */
class ArtemisEmbeddedConfigTest {

    private static final String MAIN = "classpath:artemis/broker.properties";
    private static final String TEST_OVERRIDES = "classpath:artemis/broker-test.properties";

    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    @Test
    void productionFileConfiguresRedeliveryAndDeadLetterAddressForImportCompletedQueue() {
        ConfigurationImpl configuration = apply(MAIN);

        assertThat(ArtemisEmbeddedConfig.propertyErrors(configuration.getStatus())).isEmpty();
        AddressSettings settings = configuration.getAddressSettings().get(QueueNames.IMPORT_COMPLETED);
        assertThat(settings).as("address settings for %s", QueueNames.IMPORT_COMPLETED).isNotNull();
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(6);
        assertThat(settings.getRedeliveryDelay()).isEqualTo(2_000L);
        assertThat(settings.getRedeliveryMultiplier()).isEqualTo(2.0);
        assertThat(settings.getMaxRedeliveryDelay()).isEqualTo(30_000L);
        assertThat(settings.getDeadLetterAddress().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
        // Độ trễ thực sự chạm maxRedeliveryDelay trước lần giao cuối (2s, 4s, 8s, 16s, 30s) — không phải cấu hình chết
        long delay = settings.getRedeliveryDelay();
        long totalWait = 0;
        for (int attempt = 1; attempt < settings.getMaxDeliveryAttempts(); attempt++) {
            totalWait += Math.min(delay, settings.getMaxRedeliveryDelay());
            delay = (long) (delay * settings.getRedeliveryMultiplier());
        }
        assertThat(delay).isGreaterThanOrEqualTo(settings.getMaxRedeliveryDelay());
        assertThat(totalWait).isEqualTo(60_000L);
    }

    @Test
    void productionFileRedeliversDeadLetterQueueForeverWithBackoff() {
        // Lỗi DB khi đánh dấu DEAD_LETTERED: không được rơi sang queue DLQ chung (setting "#": 10 lần, delay 0)
        AddressSettings settings = apply(MAIN).getAddressSettings().get(QueueNames.IMPORT_COMPLETED_DLQ);

        assertThat(settings).as("address settings for %s", QueueNames.IMPORT_COMPLETED_DLQ).isNotNull();
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(-1);
        assertThat(settings.getRedeliveryDelay()).isEqualTo(5_000L);
        assertThat(settings.getRedeliveryMultiplier()).isEqualTo(2.0);
        assertThat(settings.getMaxRedeliveryDelay()).isEqualTo(300_000L);
    }

    @Test
    void productionFileDeclaresDashboardEventsAsMulticastAddressWithoutPreCreatedQueues() {
        ConfigurationImpl configuration = apply(MAIN);

        // Mỗi instance tự tạo subscription non-durable; queue tạo sẵn sẽ dồn message không ai đọc
        assertThat(configuration.getAddressConfigurations())
                .filteredOn(address -> address.getName().equals(TopicNames.DASHBOARD_EVENTS))
                .singleElement()
                .satisfies(address -> {
                    assertThat(address.getRoutingTypes()).containsExactly(RoutingType.MULTICAST);
                    assertThat(address.getQueueConfigs()).isEmpty();
                });
    }

    @Test
    void productionFileCreatesDeadLetterQueueUpFrontSoDeadLettersAreNotDropped() {
        ConfigurationImpl configuration = apply(MAIN);

        assertThat(configuration.getAddressConfigurations())
                .filteredOn(address -> address.getName().equals(QueueNames.IMPORT_COMPLETED_DLQ))
                .singleElement()
                .satisfies(address -> {
                    assertThat(address.getRoutingTypes()).containsExactly(RoutingType.ANYCAST);
                    assertThat(address.getQueueConfigs()).singleElement().satisfies(queue -> {
                        assertThat(queue.getName().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
                        assertThat(queue.getAddress().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
                        assertThat(queue.getRoutingType()).isEqualTo(RoutingType.ANYCAST);
                    });
                });
    }

    @Test
    void laterFileOverridesOnlyItsOwnKeys() {
        ConfigurationImpl configuration = apply(MAIN, TEST_OVERRIDES);

        AddressSettings settings = configuration.getAddressSettings().get(QueueNames.IMPORT_COMPLETED);
        assertThat(settings.getRedeliveryDelay()).isEqualTo(100L);
        assertThat(settings.getRedeliveryMultiplier()).isEqualTo(1.0);
        // Khoá không ghi đè vẫn lấy từ file chính
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(6);
        assertThat(settings.getDeadLetterAddress().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
        AddressSettings dlq = configuration.getAddressSettings().get(QueueNames.IMPORT_COMPLETED_DLQ);
        assertThat(dlq.getRedeliveryDelay()).isEqualTo(100L);
        assertThat(dlq.getMaxDeliveryAttempts()).isEqualTo(-1);
    }

    @Test
    void fileWithoutKeysFails(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("comments-only.properties");
        Files.writeString(file, "# only a comment\n");

        assertThatThrownBy(() -> apply(file.toUri().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no keys");
    }

    @Test
    void unrecognisedStatusFormatFailsInsteadOfReportingNoErrors() {
        // Sau khi nâng Artemis, lỗi parse có thể nằm ở chỗ khác trong status → không được kết luận "không lỗi"
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.propertyErrors("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised");
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.propertyErrors("{\"brokerProperties\":{\"errors\":[]}}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ArtemisEmbeddedConfig.propertyErrors(
                "{\"properties\":{\"system-null\":{\"alder32\":\"1\",\"errors\":[]}}}")).isEmpty();
    }

    @Test
    void unknownKeyFailsInsteadOfBeingSilentlyIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("typo.properties");
        Files.writeString(file, "addressSettings.\"import-completed\".maxDeliveryAttemptz=3\n");

        assertThatThrownBy(() -> apply(file.toUri().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid Artemis broker properties")
                .hasMessageContaining("maxDeliveryAttemptz");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.loadProperties(
                List.of("classpath:artemis/does-not-exist.properties"), resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.properties");
    }

    private ConfigurationImpl apply(String... locations) {
        Properties properties = ArtemisEmbeddedConfig.loadProperties(List.of(locations), resourceLoader);
        ConfigurationImpl configuration = new ConfigurationImpl();
        ArtemisEmbeddedConfig.applyBrokerProperties(configuration, properties);
        return configuration;
    }
}
