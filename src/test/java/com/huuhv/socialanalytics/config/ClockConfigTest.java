package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    // Base64 của 32 byte 0x01 — key AES-256 hợp lệ, chỉ để AppProperties bind được
    private static final String VALID_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class)
            .withPropertyValues("app.token-encryption.key=" + VALID_KEY);

    /**
     * Clock theo app.time-zone, không theo múi giờ JVM: pod quên đặt TZ (chạy UTC) vẫn chia ngày / hiểu giờ Excel như
     * các pod khác sau load balancer.
     */
    @Test
    void clockUsesConfiguredTimeZoneNotJvmDefault() {
        TimeZone jvmDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            contextRunner
                    .withPropertyValues("app.time-zone=Asia/Ho_Chi_Minh")
                    .run(context -> assertThat(context.getBean(Clock.class).getZone())
                            .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh")));
        } finally {
            TimeZone.setDefault(jvmDefault);
        }
    }

    @Test
    void defaultsToVietnamTimeZone() {
        contextRunner.run(context -> assertThat(context.getBean(Clock.class).getZone())
                .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh")));
    }
}
