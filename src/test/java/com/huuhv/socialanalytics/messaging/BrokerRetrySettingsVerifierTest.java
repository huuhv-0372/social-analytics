package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.config.ArtemisEmbeddedConfig;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Gọi management API thật trên broker Artemis embedded (cùng giao thức với broker riêng): có/không nạp
 * {@code artemis/broker.properties}.
 */
@ExtendWith(OutputCaptureExtension.class)
class BrokerRetrySettingsVerifierTest {

    private final ApplicationContextRunner embeddedBroker = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class))
            .withPropertyValues("spring.artemis.mode=embedded", "spring.artemis.embedded.persistent=false");

    @Test
    void brokerLoadedWithBrokerPropertiesPassesVerification(CapturedOutput output) {
        embeddedBroker
                .withUserConfiguration(ArtemisEmbeddedConfig.class)
                .withBean(AppProperties.class, () -> new AppProperties(null, null, null, null, null, null,
                        new AppProperties.JmsSettings(List.of("classpath:artemis/broker.properties")), null))
                .run(context -> {
                    BrokerRetrySettingsVerifier verifier = verifier(context.getBean(ConnectionFactory.class));

                    assertThat(verifier.findProblems()).isEmpty();
                    verifier.verifyOnStartup();
                    assertThat(output).contains("Broker retry/DLQ settings for import-completed verified");
                });
    }

    @Test
    void brokerWithoutBrokerPropertiesIsReported(CapturedOutput output) {
        // Broker khởi động không có --properties broker.properties: address kế thừa setting "#"
        embeddedBroker.run(context -> {
            BrokerRetrySettingsVerifier verifier = verifier(context.getBean(ConnectionFactory.class));

            assertThat(verifier.findProblems()).satisfiesExactlyInAnyOrder(
                    problem -> assertThat(problem).contains("deadLetterAddress"),
                    problem -> assertThat(problem).contains("queue " + QueueNames.IMPORT_COMPLETED_DLQ),
                    problem -> assertThat(problem).contains("maxDeliveryAttempts"));
            verifier.verifyOnStartup();
            assertThat(output).contains("ERROR").contains("Broker is not configured with artemis/broker.properties");
        });
    }

    @Test
    void unreachableBrokerOnlyLogsWarningAndDoesNotBlockStartup(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class))
                .withUserConfiguration(BrokerRetrySettingsVerifier.class)
                .withPropertyValues("spring.artemis.mode=native", "spring.artemis.embedded.enabled=false",
                        // Port đóng → kết nối lỗi ngay
                        "spring.artemis.broker-url=tcp://localhost:1?connectTimeoutMillis=500")
                .run(context -> {
                    BrokerRetrySettingsVerifier verifier = context.getBean(BrokerRetrySettingsVerifier.class);

                    assertThatCode(verifier::verifyOnStartup).doesNotThrowAnyException();
                    assertThat(output).contains("Could not verify broker retry/DLQ settings");
                });
    }

    @Test
    void verifierIsOnlyCreatedForNativeBroker() {
        // Broker embedded đã được ArtemisEmbeddedConfig kiểm tra lúc nạp file
        embeddedBroker.withUserConfiguration(BrokerRetrySettingsVerifier.class)
                .run(context -> assertThat(context).doesNotHaveBean(BrokerRetrySettingsVerifier.class));
    }

    private static BrokerRetrySettingsVerifier verifier(ConnectionFactory connectionFactory) {
        return new BrokerRetrySettingsVerifier(connectionFactory);
    }
}
