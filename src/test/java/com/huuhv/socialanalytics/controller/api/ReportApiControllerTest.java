package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.PostReportService;
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

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

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
    void writeMethodIsNotAllowed() throws Exception {
        mockMvc.perform(post("/api/export-report").with(oauth2Login()).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(postReportService);
    }
}
