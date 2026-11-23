package com.huuhv.socialanalytics;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// MySQL riêng cho test (cùng image với docker-compose.yaml), tách khỏi DB dev.
// @ServiceConnection ghi đè spring.datasource.*; Flyway chạy migration trên DB trống mỗi lần khởi động.
// Container được dùng chung giữa các test class có cùng cấu hình context (Spring context cache).
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    }
}
