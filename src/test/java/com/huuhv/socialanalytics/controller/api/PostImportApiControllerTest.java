package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.service.PostImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Không có tham số id → không có bề mặt IDOR; owner luôn lấy từ principal trong PostImportService
@WebMvcTest(PostImportApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class PostImportApiControllerTest {

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "posts.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostImportService postImportService;

    @Test
    void importReturnsSummaryWithRowErrors() throws Exception {
        when(postImportService.importPosts(any(MultipartFile.class))).thenReturn(new ImportResultResponse(3, 1, 1, 1,
                List.of(new ImportResultResponse.RowError(4, "URL", "must not be blank"))));

        mockMvc.perform(multipart("/api/import-posts").file(FILE).with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(4))
                .andExpect(jsonPath("$.errors[0].column").value("URL"));
    }

    @Test
    void requestWithoutAuthenticationReturns401BeforeReachingService() throws Exception {
        // Chặn ở filter chain → file upload không được đọc, Service không được gọi
        mockMvc.perform(multipart("/api/import-posts").file(FILE).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
        verifyNoInteractions(postImportService);
    }

    @Test
    void importWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(multipart("/api/import-posts").file(FILE).with(user("dev@example.com")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postImportService);
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/import-posts").with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postImportService);
    }

    @Test
    void invalidFileReturns400ProblemDetail() throws Exception {
        when(postImportService.importPosts(any(MultipartFile.class)))
                .thenThrow(new BadRequestException("Only .xlsx files are accepted"));

        mockMvc.perform(multipart("/api/import-posts").file(FILE).with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Only .xlsx files are accepted"));
    }

    @Test
    void downloadTemplateReturnsXlsxAttachment() throws Exception {
        byte[] content = {'P', 'K', 3, 4};
        when(postImportService.getTemplate()).thenReturn(new ByteArrayResource(content));

        mockMvc.perform(get("/api/import-posts/template").with(user("dev@example.com")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(PostImportApiController.XLSX_MEDIA_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"posts-import-template.xlsx\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void fileOverSizeLimitReturns413() throws Exception {
        // MockMvc không áp dụng giới hạn multipart của servlet container → giả lập exception Spring ném ra
        when(postImportService.importPosts(any(MultipartFile.class)))
                .thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024));

        mockMvc.perform(multipart("/api/import-posts").file(FILE).with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("File exceeds the maximum allowed size"));
    }
}
