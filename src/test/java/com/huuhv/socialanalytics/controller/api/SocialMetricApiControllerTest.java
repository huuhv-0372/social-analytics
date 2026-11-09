package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.SocialMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SocialMetricApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class SocialMetricApiControllerTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SocialMetricService socialMetricService;

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @Test
    void listParsesIsoDateRange() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(socialMetricService.findByPost(eq(10L), eq(from), eq(null), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(response(5L)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/metrics").param("postId", "10").param("from", "2026-01-01T00:00:00Z")
                        .with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].postId").value(10));
    }

    @Test
    void listWithoutPostIdReturns400() throws Exception {
        mockMvc.perform(get("/api/metrics").with(oauth2Login()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void listWithNonPositivePostIdReturns400() throws Exception {
        mockMvc.perform(get("/api/metrics").param("postId", "0").with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // Lỗi ràng buộc trên tham số method có cùng định dạng errors[] như lỗi body
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("postId"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void listWithInvalidDateReturns400() throws Exception {
        mockMvc.perform(get("/api/metrics").param("postId", "10").param("from", "yesterday")
                        .with(oauth2Login()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void listWithDateWithoutTimeZoneReturns400() throws Exception {
        mockMvc.perform(get("/api/metrics").param("postId", "10").param("from", "2026-01-01T00:00:00")
                        .with(oauth2Login()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void listWithInvertedRangeReturns400() throws Exception {
        when(socialMetricService.findByPost(eq(10L), any(), any(), any(Pageable.class)))
                .thenThrow(new BadRequestException("from must not be after to"));

        mockMvc.perform(get("/api/metrics").param("postId", "10")
                        .param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z")
                        .with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must not be after to"));
    }

    @Test
    void listWithInvalidSortReturns400() throws Exception {
        when(socialMetricService.findByPost(eq(10L), any(), any(), any(Pageable.class)))
                .thenThrow(new BadRequestException("Sort property is not allowed"));

        mockMvc.perform(get("/api/metrics").param("postId", "10").param("sort", "post.user.email")
                        .with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requestWithoutAuthenticationReturns401BeforeReachingService() throws Exception {
        mockMvc.perform(get("/api/metrics/latest").param("postId", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void latestReturnsNewestSnapshot() throws Exception {
        when(socialMetricService.findLatest(10L)).thenReturn(response(7L));

        mockMvc.perform(get("/api/metrics/latest").param("postId", "10").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.collectedAt").value("2026-01-01T12:00:00Z"));
    }

    @Test
    void getReturnsMetric() throws Exception {
        when(socialMetricService.findById(5L)).thenReturn(response(5L));

        mockMvc.perform(get("/api/metrics/5").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.likes").value(100));
    }

    @Test
    void getOfOtherUsersMetricReturns404() throws Exception {
        when(socialMetricService.findById(99L)).thenThrow(new ResourceNotFoundException("Metric"));

        mockMvc.perform(get("/api/metrics/99").with(oauth2Login()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Metric not found"));
    }

    @Test
    void latestOfOtherUsersPostReturns404() throws Exception {
        when(socialMetricService.findLatest(99L)).thenThrow(new ResourceNotFoundException("Post"));

        mockMvc.perform(get("/api/metrics/latest").param("postId", "99").with(oauth2Login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(socialMetricService.create(any(SocialMetricRequest.class))).thenReturn(response(5L));
        String body = """
                {"postId":10,"likes":100,"shares":10,"comments":20,"followers":1000,"collectedAt":"2026-01-01T12:00:00Z"}
                """;

        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/metrics/5"));
    }

    @Test
    void createWithNegativeCountReturns400() throws Exception {
        String body = """
                {"postId":10,"likes":-1,"shares":10,"comments":20,"followers":1000,"collectedAt":"2026-01-01T12:00:00Z"}
                """;

        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("likes"));
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void createWithFutureCollectedAtReturns400() throws Exception {
        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(metricBody("2999-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void createAcceptsCurrentTimeWithOffset() throws Exception {
        when(socialMetricService.create(any(SocialMetricRequest.class))).thenReturn(response(5L));
        // "Bây giờ" của client ở +07:00 — cùng thời điểm với server dù server chạy múi giờ nào
        String clientNow = OffsetDateTime.now(ZoneOffset.ofHours(7)).minusSeconds(1).withNano(0).toString();

        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(metricBody(clientNow)))
                .andExpect(status().isCreated());
    }

    @Test
    void createAnHourInTheFutureReturns400() throws Exception {
        // Trước đây sai số 14 tiếng (LocalDateTime) cho qua giá trị này và nó thành snapshot "mới nhất"
        String future = Instant.now().plus(Duration.ofHours(1)).toString();

        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(metricBody(future)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("collectedAt"));
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void createWithoutTimeZoneReturns400() throws Exception {
        // Client quên đổi sang UTC → 400 ngay, không lưu sai giờ
        mockMvc.perform(post("/api/metrics").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(metricBody("2026-01-01T12:00:00")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(socialMetricService);
    }

    private static String metricBody(String collectedAt) {
        return """
                {"postId":10,"likes":1,"shares":1,"comments":1,"followers":1,"collectedAt":"%s"}
                """.formatted(collectedAt);
    }

    @Test
    void putIsNotSupportedForImmutableSnapshot() throws Exception {
        mockMvc.perform(put("/api/metrics/5")
                        .with(oauth2Login()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/metrics/5").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(socialMetricService).delete(5L);
    }

    @Test
    void deleteOfOtherUsersMetricReturns404() throws Exception {
        doThrow(new ResourceNotFoundException("Metric")).when(socialMetricService).delete(99L);

        mockMvc.perform(delete("/api/metrics/99").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void writeWithoutCsrfTokenReturns403() throws Exception {
        String body = """
                {"postId":10,"likes":1,"shares":1,"comments":1,"followers":1,"collectedAt":"2026-01-01T12:00:00Z"}
                """;

        mockMvc.perform(post("/api/metrics").with(oauth2Login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/metrics/5").with(oauth2Login()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(socialMetricService);
    }

    private static SocialMetricResponse response(Long id) {
        return new SocialMetricResponse(id, 10L, 100, 10, 20, 1000, COLLECTED_AT);
    }
}
