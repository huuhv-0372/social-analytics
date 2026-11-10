package com.huuhv.socialanalytics.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsImportMaxRows() {
        contextRunner.run(context ->
                assertThat(context.getBean(AppProperties.class).importSettings().maxRows()).isEqualTo(1000));
        contextRunner
                .withPropertyValues("app.import.max-rows=50")
                .run(context ->
                        assertThat(context.getBean(AppProperties.class).importSettings().maxRows()).isEqualTo(50));
    }

    @Test
    void bindsExportMaxRows() {
        contextRunner.run(context ->
                assertThat(context.getBean(AppProperties.class).exportSettings().maxRows()).isEqualTo(10000));
        contextRunner
                .withPropertyValues("app.export.max-rows=500")
                .run(context ->
                        assertThat(context.getBean(AppProperties.class).exportSettings().maxRows()).isEqualTo(500));
    }

    @Test
    void failsStartupWhenExportMaxRowsIsNotPositive() {
        contextRunner
                .withPropertyValues("app.export.max-rows=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsExportMinInterval() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).exportSettings().minInterval())
                .isEqualTo(Duration.ofSeconds(10)));
        contextRunner
                .withPropertyValues("app.export.min-interval=0")
                .run(context -> assertThat(context.getBean(AppProperties.class).exportSettings().minInterval())
                        .isZero());
    }

    @Test
    void failsStartupWhenExportMinIntervalIsNegative() {
        contextRunner
                .withPropertyValues("app.export.min-interval=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsImportMaxUncompressedSize() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).importSettings()
                .maxUncompressedSize()).isEqualTo(DataSize.ofMegabytes(20)));
        contextRunner
                .withPropertyValues("app.import.max-uncompressed-size=5MB")
                .run(context -> assertThat(context.getBean(AppProperties.class).importSettings()
                        .maxUncompressedSize()).isEqualTo(DataSize.ofMegabytes(5)));
    }

    @Test
    void failsStartupWhenImportMaxUncompressedSizeIsNotPositive() {
        contextRunner
                .withPropertyValues("app.import.max-uncompressed-size=0B")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenImportMaxRowsIsNotPositive() {
        contextRunner
                .withPropertyValues("app.import.max-rows=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
