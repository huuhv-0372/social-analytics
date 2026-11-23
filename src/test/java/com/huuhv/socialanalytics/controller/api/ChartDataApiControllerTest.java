package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.dto.response.ImportStatisticsResponse;
import com.huuhv.socialanalytics.dto.response.ChartDataResponse.ProviderSeries;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.service.ChartDataService;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Không có tham số id → không có bề mặt IDOR; dữ liệu lọc theo owner trong Service (OwnershipIsolationTest).
// Ẩn danh → 401 và method ghi → 403 CSRF kiểm trong ApiSecurityMatrixTest.
@WebMvcTest(ChartDataApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class ChartDataApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChartDataService chartDataService;

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    // StaleSessionFilter (SecurityConfig) kiểm tra user của session qua service này; mock trả null = user hợp lệ
    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void returnsChartDataAsJson() throws Exception {
        when(chartDataService.getChartData(2)).thenReturn(new ChartDataResponse("Asia/Ho_Chi_Minh",
                List.of(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7)),
                List.of(new ProviderSeries(Provider.FACEBOOK, Arrays.asList(null, 10L), Arrays.asList(null, 1L),
                        Arrays.asList(null, 2L), Arrays.asList(null, 100L))),
                Instant.parse("2026-11-07T03:00:05Z"),
                new ImportStatisticsResponse(3, 25, 20, 5, Instant.parse("2026-11-06T18:15:00Z"))));

        mockMvc.perform(get("/api/chart-data").param("days", "2").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.zone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.labels[0]").value("2026-11-06"))
                .andExpect(jsonPath("$.labels[1]").value("2026-11-07"))
                .andExpect(jsonPath("$.series[0].provider").value("FACEBOOK"))
                // Ngày chưa có dữ liệu là null (biểu đồ vẽ gap), không phải 0
                .andExpect(jsonPath("$.series[0].likes[0]").value(nullValue()))
                .andExpect(jsonPath("$.series[0].likes[1]").value(10))
                .andExpect(jsonPath("$.lastUpdatedAt").value("2026-11-07T03:00:05Z"))
                .andExpect(jsonPath("$.importStatistics.totalImports").value(3))
                .andExpect(jsonPath("$.importStatistics.lastImportAt").value("2026-11-06T18:15:00Z"));
    }

    @Test
    void defaultsToSevenDays() throws Exception {
        mockMvc.perform(get("/api/chart-data").with(oauth2Login()))
                .andExpect(status().isOk());

        verify(chartDataService).getChartData(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "31", "abc", "1.5"})
    void invalidDaysReturns400(String days) throws Exception {
        mockMvc.perform(get("/api/chart-data").param("days", days).with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(chartDataService);
    }

    @Test
    void outOfRangeDaysReportsFieldError() throws Exception {
        mockMvc.perform(get("/api/chart-data").param("days", "31").with(oauth2Login()))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("days"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void sessionWithoutKnownUserReturns401() throws Exception {
        when(chartDataService.getChartData(7)).thenThrow(new AuthenticationRequiredException());

        mockMvc.perform(get("/api/chart-data").with(oauth2Login()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
