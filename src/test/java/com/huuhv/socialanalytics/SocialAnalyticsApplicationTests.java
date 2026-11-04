package com.huuhv.socialanalytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Cần MySQL đang chạy (docker compose up -d). Sẽ chuyển sang Testcontainers ở Task 7.4.
@SpringBootTest
@ActiveProfiles("test")
class SocialAnalyticsApplicationTests {

    @Test
    void contextLoads() {
    }
}
