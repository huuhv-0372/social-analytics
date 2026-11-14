package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Nguồn thời gian dùng chung: inject {@link Clock} thay vì gọi {@code now()} trực tiếp để test cố định được thời gian.
 * <p>
 * Múi giờ của clock = múi giờ nghiệp vụ {@code app.time-zone} ({@link AppProperties#timeZone()}, env {@code APP_TZ}),
 * <b>không</b> theo múi giờ JVM: quyết định ranh giới ngày của biểu đồ, giờ trong file Excel import/export và giờ hiển
 * thị. Chạy nhiều instance sau load balancer, 1 pod quên đặt {@code TZ} (image mặc định UTC) vẫn cho cùng kết quả —
 * nếu theo JVM, file export ở pod UTC import ở pod +07 sẽ lệch 7 giờ (sinh snapshot trùng sai giờ) và biểu đồ chia
 * ngày khác nhau tuỳ request rơi vào pod nào. DB luôn lưu UTC nên múi giờ này không ảnh hưởng dữ liệu đã lưu.
 * <p>
 * {@code @EnableConfigurationProperties}: test slice ({@code @DataJpaTest}) {@code @Import} class này không quét
 * {@code @ConfigurationPropertiesScan} của application class.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ClockConfig {

    @Bean
    Clock clock(AppProperties appProperties) {
        Clock clock = Clock.system(appProperties.timeZone());
        // Múi giờ JVM chỉ ảnh hưởng giờ in trong log — in cả 2 để phát hiện pod cấu hình lệch
        log.info("Server time zone: {} (JVM default: {})", clock.getZone(), ZoneId.systemDefault());
        return clock;
    }
}
