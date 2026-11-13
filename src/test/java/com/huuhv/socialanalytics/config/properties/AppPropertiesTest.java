package com.huuhv.socialanalytics.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
    void bindsSocialCrawlerExecutorSettings() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).async().socialCrawler())
                .isEqualTo(new AppProperties.SocialCrawler(8, 8, 100, Duration.ofSeconds(30))));
        contextRunner
                .withPropertyValues("app.async.social-crawler.core-pool-size=1",
                        "app.async.social-crawler.max-pool-size=2",
                        "app.async.social-crawler.queue-capacity=0",
                        "app.async.social-crawler.await-termination=5s")
                .run(context -> assertThat(context.getBean(AppProperties.class).async().socialCrawler())
                        .isEqualTo(new AppProperties.SocialCrawler(1, 2, 0, Duration.ofSeconds(5))));
    }

    @Test
    void failsStartupWhenSocialCrawlerSettingsAreInvalid() {
        contextRunner
                .withPropertyValues("app.async.social-crawler.core-pool-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.async.social-crawler.core-pool-size=9",
                        "app.async.social-crawler.max-pool-size=8")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.async.social-crawler.queue-capacity=-1")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.async.social-crawler.await-termination=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsUpdateMetricsJobSettings() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).jobs().updateMetrics())
                .isEqualTo(new AppProperties.UpdateMetrics(100, Duration.ofMinutes(50), Duration.ofMinutes(55),
                        Duration.ofMinutes(5))));
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.batch-size=20", "app.jobs.update-metrics.timeout=4m",
                        "app.jobs.update-metrics.lock-at-most-for=270s",
                        "app.jobs.update-metrics.lock-at-least-for=1m",
                        // Cron đọc bằng placeholder trong @Scheduled — key lạ trong nhóm không làm hỏng binding
                        "app.jobs.update-metrics.cron=0 */5 * * * *")
                .run(context -> assertThat(context.getBean(AppProperties.class).jobs().updateMetrics())
                        .isEqualTo(new AppProperties.UpdateMetrics(20, Duration.ofMinutes(4), Duration.ofSeconds(270),
                                Duration.ofMinutes(1))));
    }

    @Test
    void failsStartupWhenUpdateMetricsSettingsAreInvalid() {
        // Client mạng xã hội nhận tối đa 100 id mỗi lần
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.batch-size=101")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenJobLockDurationsAreInconsistent() {
        // Khoá hết hạn trước khi job hết thời lượng → instance khác chạy chồng
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.timeout=50m",
                        "app.jobs.update-metrics.lock-at-most-for=50m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.lock-at-least-for=56m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.lock-at-least-for=-1s")
                .run(context -> assertThat(context).hasFailed());
        // Không giữ khoá tối thiểu là hợp lệ (chạy 1 instance)
        contextRunner
                .withPropertyValues("app.jobs.update-metrics.lock-at-least-for=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void bindsResendImportCompletedJobSettings() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).jobs().resendImportCompleted())
                .isEqualTo(new AppProperties.ResendImportCompleted(Duration.ofMinutes(10), 100,
                        Duration.ofMinutes(9), Duration.ofMinutes(1))));
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.min-age=3m",
                        "app.jobs.resend-import-completed.batch-size=20",
                        "app.jobs.resend-import-completed.lock-at-most-for=50s",
                        "app.jobs.resend-import-completed.lock-at-least-for=10s",
                        "app.jobs.resend-import-completed.cron=0 * * * * *")
                .run(context -> assertThat(context.getBean(AppProperties.class).jobs().resendImportCompleted())
                        .isEqualTo(new AppProperties.ResendImportCompleted(Duration.ofMinutes(3), 20,
                                Duration.ofSeconds(50), Duration.ofSeconds(10))));
    }

    @Test
    void failsStartupWhenResendImportCompletedSettingsAreInvalid() {
        // min-age 0 → gửi lại cả message đang được broker retry / listener đang xử lý
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.min-age=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.batch-size=1001")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.lock-at-least-for=10m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jobs.resend-import-completed.lock-at-most-for=0s",
                        "app.jobs.resend-import-completed.lock-at-least-for=0s")
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

    @Test
    void bindsEmbeddedBrokerPropertiesInOrder() {
        // Mặc định: chỉ file chính — cũng là file broker riêng của docker compose nạp
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).jms().embeddedBrokerProperties())
                .containsExactly("classpath:artemis/broker.properties"));
        contextRunner
                .withPropertyValues("app.jms.embedded-broker-properties="
                        + "classpath:artemis/broker.properties,classpath:artemis/broker-test.properties")
                .run(context -> assertThat(context.getBean(AppProperties.class).jms().embeddedBrokerProperties())
                        .containsExactly("classpath:artemis/broker.properties",
                                "classpath:artemis/broker-test.properties"));
    }

    @Test
    void failsStartupWhenEmbeddedBrokerPropertiesAreEmpty() {
        // Không nạp file nào: broker embedded chạy không có retry/DLQ
        contextRunner
                .withPropertyValues("app.jms.embedded-broker-properties=")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.jms.embedded-broker-properties[0]= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsSoapExchangeRateSettings() {
        contextRunner.run(context -> {
            AppProperties.ExchangeRate exchangeRate = context.getBean(AppProperties.class).soap().exchangeRate();
            // Không có mặc định trỏ vào chính app (endpoint mock tắt ở production) → không đặt = tắt tỷ giá
            assertThat(exchangeRate.url()).isNull();
            assertThat(exchangeRate.hasUrl()).isFalse();
            assertThat(exchangeRate.warmUpOnStartup()).isTrue();
            assertThat(exchangeRate.mockEndpoint().maxRequestSize()).isEqualTo(DataSize.ofKilobytes(16));
            assertThat(exchangeRate.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(exchangeRate.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(exchangeRate.baseCurrency()).isEqualTo("USD");
            assertThat(exchangeRate.quoteCurrency()).isEqualTo("VND");
            assertThat(exchangeRate.cacheTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(exchangeRate.retryAfterFailure()).isEqualTo(Duration.ofMinutes(1));
            // Endpoint mock public → mặc định tắt (production-safe)
            assertThat(exchangeRate.mockEndpoint().enabled()).isFalse();
        });
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.url=https://rates.example.com/ws",
                        "app.soap.exchange-rate.connect-timeout=1s", "app.soap.exchange-rate.read-timeout=4s",
                        "app.soap.exchange-rate.mock-endpoint.enabled=true")
                .run(context -> {
                    AppProperties.ExchangeRate exchangeRate = context.getBean(AppProperties.class).soap().exchangeRate();
                    assertThat(exchangeRate.url()).isEqualTo(URI.create("https://rates.example.com/ws"));
                    assertThat(exchangeRate.hasUrl()).isTrue();
                    assertThat(exchangeRate.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(exchangeRate.readTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(exchangeRate.mockEndpoint().enabled()).isTrue();
                });
    }

    /** {@code url: ${SOAP_EXCHANGE_RATE_URL:}} trong application.yml bind chuỗi rỗng → coi như không đặt, không lỗi. */
    @Test
    void emptySoapUrlDisablesExchangeRate() {
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.url=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppProperties.class).soap().exchangeRate().hasUrl()).isFalse();
                });
    }

    @Test
    void failsStartupWhenSoapMockMaxRequestSizeIsNotPositive() {
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.mock-endpoint.max-request-size=0B")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsImportMinIntervalAndRejectsNegative() {
        contextRunner.run(context -> assertThat(context.getBean(AppProperties.class).importSettings().minInterval())
                .isEqualTo(Duration.ofSeconds(10)));
        contextRunner
                .withPropertyValues("app.import.min-interval=0s")
                .run(context -> assertThat(context.getBean(AppProperties.class).importSettings().minInterval())
                        .isZero());
        contextRunner
                .withPropertyValues("app.import.min-interval=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenSoapUrlIsNotHttpOrHasUserInfo() {
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.url=file:///etc/passwd")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.url=http://user:secret@localhost:8080/services")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.url=/services")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenExchangeRatePairOrCacheSettingsAreInvalid() {
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.base-currency=usd")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.base-currency=VND")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.cache-ttl=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.retry-after-failure=-1s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.retry-after-failure=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenSoapTimeoutIsNotPositive() {
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.read-timeout=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.soap.exchange-rate.connect-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Giá trị thật trong application.yml / application-dev.yml phải bind + validate được (sai định dạng → app không chạy). */
    @Test
    void applicationYmlAndDevProfileBindValidSettings() {
        contextRunner
                .withInitializer(context -> loadYaml(context.getEnvironment(), "application.yml"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(properties.jobs().updateMetrics().lockAtMostFor()).isEqualTo(Duration.ofMinutes(55));
                    assertThat(properties.jobs().resendImportCompleted().minAge()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.async().socialCrawler().corePoolSize()).isEqualTo(8);
                    // Production: endpoint SOAP mock public phải tắt mặc định
                    assertThat(properties.soap().exchangeRate().mockEndpoint().enabled()).isFalse();
                });
        contextRunner
                .withInitializer(context -> {
                    // Profile dev ghi đè application.yml → nạp trước để có độ ưu tiên cao hơn
                    loadYaml(context.getEnvironment(), "application-dev.yml");
                    loadYaml(context.getEnvironment(), "application.yml");
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties.UpdateMetrics updateMetrics =
                            context.getBean(AppProperties.class).jobs().updateMetrics();
                    assertThat(updateMetrics.timeout()).isEqualTo(Duration.ofMinutes(4));
                    assertThat(updateMetrics.lockAtMostFor()).isEqualTo(Duration.ofSeconds(270));
                    assertThat(context.getBean(AppProperties.class).soap().exchangeRate().mockEndpoint().enabled())
                            .isTrue();
                });
    }

    private static void loadYaml(ConfigurableEnvironment environment, String name) {
        try {
            new YamlPropertySourceLoader().load(name, new ClassPathResource(name))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
