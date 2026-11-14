package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.dto.response.MetricImportResultResponse;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.SocialMetricImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SocialMetricImportApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class SocialMetricImportApiControllerTest {

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "metrics.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{'P', 'K', 3, 4});

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SocialMetricImportService socialMetricImportService;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void returnsImportResultWithRowErrors() throws Exception {
        when(socialMetricImportService.importMetrics(any())).thenReturn(new MetricImportResultResponse(3, 2, 1,
                List.of(new ImportResultResponse.RowError(4, "External ID", "post not found"))));

        mockMvc.perform(multipart("/api/import-metrics").file(FILE).with(oauth2Login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(4))
                .andExpect(jsonPath("$.errors[0].column").value("External ID"))
                .andExpect(jsonPath("$.errors[0].message").value("post not found"));
    }

    @Test
    void downloadsTemplateAsAttachment() throws Exception {
        byte[] content = {'P', 'K', 3, 4};
        when(socialMetricImportService.getTemplate()).thenReturn(new ByteArrayResource(content));

        mockMvc.perform(get("/api/import-metrics/template").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(GenericExcelExporter.XLSX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"metrics-import-template.xlsx\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void realTemplateFileIsServed() throws Exception {
        ClassPathResource template = new ClassPathResource("excel/metrics-import-template.xlsx");
        when(socialMetricImportService.getTemplate()).thenReturn(template);

        mockMvc.perform(get("/api/import-metrics/template").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(template.getContentAsByteArray()));
    }

    @Test
    void invalidFileReturns400ProblemDetail() throws Exception {
        when(socialMetricImportService.importMetrics(any()))
                .thenThrow(new BadRequestException("Only .xlsx files are accepted"));

        mockMvc.perform(multipart("/api/import-metrics").file(FILE).with(oauth2Login()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Only .xlsx files are accepted"));
    }

    @Test
    void missingFilePartReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(multipart("/api/import-metrics").with(oauth2Login()).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricImportService);
    }

    @Test
    void anonymousOrMissingCsrfIsRejectedBeforeService() throws Exception {
        mockMvc.perform(multipart("/api/import-metrics").file(FILE).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/import-metrics").file(FILE).with(oauth2Login()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(socialMetricImportService);
    }
}
