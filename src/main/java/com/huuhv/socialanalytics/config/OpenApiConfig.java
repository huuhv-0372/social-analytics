package com.huuhv.socialanalytics.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Thông tin tài liệu OpenAPI (Swagger UI). Swagger chỉ bật ở profile dev ({@code springdoc.*} trong application*.yml).
 */
@Configuration
public class OpenApiConfig {

    // TẠM THỜI tới Epic 3: API xác thực bằng HTTP Basic — Swagger UI hiện nút Authorize để nhập tài khoản
    private static final String BASIC_AUTH = "basicAuth";

    @Bean
    OpenAPI socialAnalyticsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Social Analytics API")
                        .version("v1")
                        .description("REST API tổng hợp và theo dõi tương tác bài viết Facebook & Twitter (X)."))
                .components(new Components().addSecuritySchemes(BASIC_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description("Tài khoản API_USER_NAME / API_USER_PASSWORD")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
