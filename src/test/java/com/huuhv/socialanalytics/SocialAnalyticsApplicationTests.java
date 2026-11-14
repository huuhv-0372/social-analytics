package com.huuhv.socialanalytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// MySQL do Testcontainers tạo (cần Docker) — không kết nối DB dev trong .env
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SocialAnalyticsApplicationTests {

    @Test
    void contextLoads() {
    }
}
