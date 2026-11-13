package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.Test;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Broker Artemis embedded chỉ chạy khi app dùng nó. Boot tạo broker embedded theo
 * {@code spring.artemis.embedded.enabled} (mặc định true) mà không xét {@code spring.artemis.mode} → kiểm tra bằng
 * chính {@code application.yml} / {@code application-dev.yml}.
 */
class ArtemisEmbeddedBrokerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class))
            .withUserConfiguration(ArtemisEmbeddedConfig.class)
            .withBean(AppProperties.class, () -> new AppProperties(null, null, null, null, null, null,
                    new AppProperties.JmsSettings(List.of("classpath:artemis/broker.properties"))))
            .withPropertyValues("ARTEMIS_USER=test", "ARTEMIS_PASSWORD=test");

    @Test
    void productionConfigDoesNotStartEmbeddedBroker() {
        contextRunner
                .withInitializer(context -> loadYaml(context.getEnvironment(), "application.yml"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddedActiveMQ.class);
                });
    }

    @Test
    void devProfileStartsEmbeddedBrokerByDefault() {
        contextRunner
                .withInitializer(context -> loadDevProfile(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddedActiveMQ.class);
                });
    }

    @Test
    void devProfileWithNativeModeAndEmbeddedDisabledDoesNotStartEmbeddedBroker() {
        contextRunner
                .withInitializer(context -> loadDevProfile(context.getEnvironment()))
                .withPropertyValues("ARTEMIS_MODE=native", "ARTEMIS_EMBEDDED_ENABLED=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddedActiveMQ.class);
                });
    }

    @Test
    void nativeModeWithEmbeddedBrokerEnabledFailsFast() {
        // Chỉ đổi ARTEMIS_MODE, quên tắt broker embedded → dừng khởi động với hướng dẫn, không chạy thừa broker
        contextRunner
                .withInitializer(context -> loadDevProfile(context.getEnvironment()))
                .withPropertyValues("ARTEMIS_MODE=native")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("spring.artemis.embedded.enabled=false"));
    }

    /** Profile dev ghi đè application.yml → nạp trước để có độ ưu tiên cao hơn. */
    private static void loadDevProfile(ConfigurableEnvironment environment) {
        loadYaml(environment, "application-dev.yml");
        loadYaml(environment, "application.yml");
    }

    private static void loadYaml(ConfigurableEnvironment environment, String name) {
        try {
            new YamlPropertySourceLoader().load(name, new ClassPathResource(name))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
