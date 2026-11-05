package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsDefaultsWhenNotConfigured() {
        contextRunner.run(context -> {
            AppProperties.Pagination pagination = context.getBean(AppProperties.class).pagination();
            assertThat(pagination.defaultSize()).isEqualTo(20);
            assertThat(pagination.maxSize()).isEqualTo(100);
        });
    }

    @Test
    void bindsConfiguredValues() {
        contextRunner
                .withPropertyValues("app.pagination.default-size=10", "app.pagination.max-size=50")
                .run(context -> {
                    AppProperties.Pagination pagination = context.getBean(AppProperties.class).pagination();
                    assertThat(pagination.defaultSize()).isEqualTo(10);
                    assertThat(pagination.maxSize()).isEqualTo(50);
                });
    }

    @Test
    void failsStartupWhenSizeIsNotPositive() {
        contextRunner
                .withPropertyValues("app.pagination.max-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenDefaultSizeExceedsMaxSize() {
        contextRunner
                .withPropertyValues("app.pagination.default-size=200", "app.pagination.max-size=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
