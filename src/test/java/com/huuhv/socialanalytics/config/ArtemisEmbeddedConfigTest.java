package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.messaging.QueueNames;
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
 * phải khớp {@link QueueNames}, không có khoá sai (broker riêng chỉ bỏ qua khoá sai, không báo lỗi).
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
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(3);
        assertThat(settings.getRedeliveryDelay()).isEqualTo(2_000L);
        assertThat(settings.getRedeliveryMultiplier()).isEqualTo(2.0);
        assertThat(settings.getMaxRedeliveryDelay()).isEqualTo(30_000L);
        assertThat(settings.getDeadLetterAddress().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
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
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(3);
        assertThat(settings.getDeadLetterAddress().toString()).isEqualTo(QueueNames.IMPORT_COMPLETED_DLQ);
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
