package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.service.ChartDataService;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.PostImportService;
import com.huuhv.socialanalytics.service.PostReportService;
import com.huuhv.socialanalytics.service.SocialMetricImportService;
import com.huuhv.socialanalytics.service.SocialMetricReportService;
import com.huuhv.socialanalytics.service.PostService;
import com.huuhv.socialanalytics.service.SocialMetricService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ma trận bảo mật cho <b>mọi</b> endpoint {@code /api/**} (CLAUDE.md mục 7): chưa đăng nhập → 401, request ghi thiếu
 * CSRF → 403 — cả hai bị chặn ở filter chain, Service không bao giờ được gọi. Thêm endpoint mới thì thêm vào
 * {@link #allEndpoints()} / {@link #writeEndpoints()}. Kiểm tra IDOR / input sai nằm trong test của từng controller
 * và {@code service/OwnershipIsolationTest}.
 */
@WebMvcTest({PostApiController.class, SocialMetricApiController.class, PostImportApiController.class,
        SocialMetricImportApiController.class, ReportApiController.class, ChartDataApiController.class})
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class ApiSecurityMatrixTest {

    private static final String POST_BODY = """
            {"provider":"FACEBOOK","externalId":"fb-1","url":"https://facebook.com/posts/1","content":"Hello"}""";
    private static final String METRIC_BODY = """
            {"postId":10,"likes":1,"shares":1,"comments":1,"followers":1,"collectedAt":"2026-01-01T00:00:00"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private SocialMetricService socialMetricService;

    @MockitoBean
    private PostImportService postImportService;

    @MockitoBean
    private PostReportService postReportService;

    @MockitoBean
    private SocialMetricImportService socialMetricImportService;

    @MockitoBean
    private SocialMetricReportService socialMetricReportService;

    @MockitoBean
    private ChartDataService chartDataService;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    // StaleSessionFilter (SecurityConfig) kiểm tra user của session qua service này; mock trả null = user hợp lệ
    @MockitoBean
    private CurrentUserService currentUserService;

    @AfterEach
    void serviceIsNeverReached() {
        verifyNoInteractions(postService, socialMetricService, postImportService, postReportService, chartDataService,
                socialMetricImportService, socialMetricReportService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    void anonymousRequestReturns401ProblemDetail(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request)
            throws Exception {
        // Kèm CSRF hợp lệ để chắc chắn bị chặn vì chưa đăng nhập, không phải vì thiếu CSRF
        mockMvc.perform(request.get().with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                // REST không redirect về trang login, không gửi challenge Basic
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeEndpoints")
    void authenticatedWriteWithoutCsrfReturns403(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request)
            throws Exception {
        mockMvc.perform(request.get().with(oauth2Login()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeEndpoints")
    void authenticatedWriteWithInvalidCsrfReturns403(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request)
            throws Exception {
        mockMvc.perform(request.get().with(oauth2Login()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    static Stream<Arguments> allEndpoints() {
        return Stream.concat(Stream.of(
                endpoint("GET /api/posts", () -> get("/api/posts")),
                endpoint("GET /api/posts/{id}", () -> get("/api/posts/10")),
                endpoint("GET /api/metrics", () -> get("/api/metrics").param("postId", "10")),
                endpoint("GET /api/metrics/latest", () -> get("/api/metrics/latest").param("postId", "10")),
                endpoint("GET /api/metrics/{id}", () -> get("/api/metrics/5")),
                endpoint("GET /api/import-posts/template", () -> get("/api/import-posts/template")),
                endpoint("GET /api/export-report", () -> get("/api/export-report")),
                endpoint("GET /api/export-metrics", () -> get("/api/export-metrics")),
                endpoint("GET /api/import-metrics/template", () -> get("/api/import-metrics/template")),
                endpoint("GET /api/chart-data", () -> get("/api/chart-data"))), writeEndpoints());
    }

    static Stream<Arguments> writeEndpoints() {
        return Stream.of(
                endpoint("POST /api/posts", () -> post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON).content(POST_BODY)),
                endpoint("PUT /api/posts/{id}", () -> put("/api/posts/10")
                        .contentType(MediaType.APPLICATION_JSON).content(POST_BODY)),
                endpoint("DELETE /api/posts/{id}", () -> delete("/api/posts/10")),
                endpoint("POST /api/metrics", () -> post("/api/metrics")
                        .contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY)),
                endpoint("DELETE /api/metrics/{id}", () -> delete("/api/metrics/5")),
                endpoint("POST /api/import-posts", () -> multipart("/api/import-posts")
                        .file(new MockMultipartFile("file", "posts.xlsx", "application/octet-stream", new byte[]{1}))),
                endpoint("POST /api/import-metrics", () -> multipart("/api/import-metrics")
                        .file(new MockMultipartFile("file", "metrics.xlsx", "application/octet-stream",
                                new byte[]{1}))));
    }

    private static Arguments endpoint(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request) {
        return Arguments.of(name, request);
    }
}
