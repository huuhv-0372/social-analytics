package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.PostReportService;
import com.huuhv.socialanalytics.service.SocialMetricReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Không có tham số id → không có bề mặt IDOR; dữ liệu lọc theo owner trong query (PostRepositoryTest).
// GET không đổi trạng thái → không cần CSRF; method ghi tới endpoint này không được hỗ trợ.
@WebMvcTest(ReportApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class ReportApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostReportService postReportService;

    @MockitoBean
    private SocialMetricReportService socialMetricReportService;

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    // StaleSessionFilter (SecurityConfig) kiểm tra user của session qua service này; mock trả null = user hợp lệ
    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void exportStreamsXlsxAttachment() throws Exception {
        byte[] content = {'P', 'K', 3, 4};
        when(postReportService.exportReport())
                .thenReturn(new ExportFile("posts-report-20260101-090000.xlsx", out -> out.write(content)));

        // StreamingResponseBody ghi file ở luồng async → cần asyncDispatch để lấy body
        MvcResult started = mockMvc.perform(get("/api/export-report").with(oauth2Login()))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType(GenericExcelExporter.XLSX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"posts-report-20260101-090000.xlsx\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void exportUsesConfiguredAsyncTimeoutInsteadOfContainerDefault() throws Exception {
        when(postReportService.exportReport())
                .thenReturn(new ExportFile("posts-report-20260101-090000.xlsx", out -> out.write(1)));

        MvcResult started = mockMvc.perform(get("/api/export-report").with(oauth2Login()))
                .andExpect(request().asyncStarted())
                .andReturn();

        // spring.mvc.async.request-timeout (60s) áp vào request async của StreamingResponseBody, không phải 30s của Tomcat
        assertThat(started.getRequest().getAsyncContext().getTimeout()).isEqualTo(Duration.ofSeconds(60).toMillis());
    }

    // Không stub Service: chứng minh request ẩn danh bị filter chain chặn trước khi tới Service
    @Test
    void anonymousRequestIsRejectedByFilterChainBeforeService() throws Exception {
        mockMvc.perform(get("/api/export-report"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        verifyNoInteractions(postReportService);
    }

    @Test
    void reportOverRowLimitReturns400ProblemDetail() throws Exception {
        when(postReportService.exportReport()).thenThrow(new BadRequestException("Report exceeds the maximum of 2 rows"));

        mockMvc.perform(get("/api/export-report").with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Report exceeds the maximum of 2 rows"));
    }

    @Test
    void tooFrequentExportReturns429WithRetryAfter() throws Exception {
        when(postReportService.exportReport()).thenThrow(new TooManyRequestsException(Duration.ofMillis(1500)));

        mockMvc.perform(get("/api/export-report").with(oauth2Login()))
                .andExpect(status().isTooManyRequests())
                // 1.5 giây làm tròn lên 2 để client không gọi lại quá sớm
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void exportMetricsPassesRangeAndStreamsXlsxAttachment() throws Exception {
        byte[] content = {'P', 'K', 3, 4};
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");
        when(socialMetricReportService.exportMetrics(from, to))
                .thenReturn(new ExportFile("metrics-report-20260201-090000.xlsx", out -> out.write(content)));

        MvcResult started = mockMvc.perform(get("/api/export-metrics").with(oauth2Login())
                        .param("from", "2026-01-01T07:00:00+07:00").param("to", "2026-01-31T23:59:59Z"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType(GenericExcelExporter.XLSX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"metrics-report-20260201-090000.xlsx\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void exportMetricsWithoutRangeExportsEverything() throws Exception {
        when(socialMetricReportService.exportMetrics(null, null))
                .thenReturn(new ExportFile("metrics-report-20260201-090000.xlsx", out -> out.write(1)));

        mockMvc.perform(get("/api/export-metrics").with(oauth2Login()))
                .andExpect(request().asyncStarted());
        verify(socialMetricReportService).exportMetrics(null, null);
    }

    @Test
    void exportMetricsRejectsDateTimeWithoutZone() throws Exception {
        // Thiếu múi giờ không xác định được mốc tuyệt đối → 400, Service không được gọi
        mockMvc.perform(get("/api/export-metrics").with(oauth2Login()).param("from", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(socialMetricReportService);
    }

    @Test
    void exportMetricsErrorsAreProblemDetails() throws Exception {
        when(socialMetricReportService.exportMetrics(any(), any()))
                .thenThrow(new BadRequestException("from must not be after to"))
                .thenThrow(new TooManyRequestsException(Duration.ofSeconds(3)));

        mockMvc.perform(get("/api/export-metrics").with(oauth2Login())
                        .param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must not be after to"));
        mockMvc.perform(get("/api/export-metrics").with(oauth2Login()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"));
    }

    @Test
    void writeMethodIsNotAllowed() throws Exception {
        mockMvc.perform(post("/api/export-report").with(oauth2Login()).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(postReportService);
    }
}
