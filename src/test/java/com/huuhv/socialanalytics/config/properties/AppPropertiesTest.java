package com.huuhv.socialanalytics.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    // Base64 của 32 byte 0x01 — key AES-256 hợp lệ, chỉ dùng trong test
    private static final String VALID_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("app.token-encryption.key=" + VALID_KEY);

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

    @Test
    void bindsTokenEncryptionKey() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).tokenEncryption().keyBytes())
                .hasSize(32)
                .containsOnly((byte) 1));
    }

    @Test
    void failsStartupWhenTokenEncryptionKeyIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.token-encryption.key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenTokenEncryptionKeyIsNotAes256() {
        // Base64 hợp lệ nhưng chỉ 16 byte (AES-128)
        contextRunner
                .withPropertyValues("app.token-encryption.key=AQEBAQEBAQEBAQEBAQEBAQ==")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.token-encryption.key=not-base64!!")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void toStringDoesNotLeakTokenEncryptionKey() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).toString())
                .doesNotContain(VALID_KEY)
                .contains("key=******"));
    }

    @Test
    void bindingFailureMessageDoesNotLeakInvalidKey() {
        contextRunner
                .withPropertyValues("app.token-encryption.key=c2VjcmV0LWJ1dC10b28tc2hvcnQ=")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .message()
                        .doesNotContain("c2VjcmV0LWJ1dC10b28tc2hvcnQ="));
    }

    @Test
    void bindsOAuth2Timeouts() {
        contextRunner.run(context -> {
            AppProperties.OAuth2Client oauth2 = context.getBean(AppProperties.class).oauth2();
            assertThat(oauth2.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(oauth2.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
        contextRunner
                .withPropertyValues("app.oauth2.connect-timeout=2s", "app.oauth2.read-timeout=3s")
                .run(context -> {
                    AppProperties.OAuth2Client oauth2 = context.getBean(AppProperties.class).oauth2();
                    assertThat(oauth2.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(oauth2.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void failsStartupWhenOAuth2TimeoutIsNotPositive() {
        // 0 = không timeout với HttpURLConnection → không cho phép
        contextRunner
                .withPropertyValues("app.oauth2.read-timeout=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.oauth2.connect-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
