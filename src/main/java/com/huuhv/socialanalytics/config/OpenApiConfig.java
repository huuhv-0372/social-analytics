package com.huuhv.socialanalytics.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Thông tin tài liệu OpenAPI (Swagger UI). Swagger chỉ bật ở profile dev ({@code springdoc.*} trong application*.yml).
 * <p>
 * Không khai báo security scheme: API xác thực bằng session cookie của đăng nhập OAuth2 (Facebook / X) — đăng nhập trên
 * trình duyệt trước, Swagger UI cùng origin tự gửi cookie session và header CSRF.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI socialAnalyticsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Social Analytics API")
                        .version("v1")
                        .description("REST API tổng hợp và theo dõi tương tác bài viết Facebook & Twitter (X). "
                                + "Đăng nhập bằng Facebook hoặc X ở trang /login trước khi gọi API."));
    }
}
