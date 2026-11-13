package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.dto.response.ImportStatisticsResponse;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import com.huuhv.socialanalytics.security.CsrfCookies;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.DashboardService;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class DashboardControllerTest {

    /** {@code <script src="https://cdn.jsdelivr.net/npm/…" integrity="sha384-…" crossorigin="anonymous">} */
    private static final Pattern CDN_SCRIPT = Pattern.compile(
            "<script src=\"https://cdn\\.jsdelivr\\.net/npm/([^\"]+)\"\\s+integrity=\"sha384-[A-Za-z0-9+/=]{64}\"\\s+"
                    + "crossorigin=\"anonymous\"></script>");

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DashboardResponse EMPTY_DASHBOARD = new DashboardResponse(null, null, SERVER_ZONE);

    @Autowired
    private MockMvc mockMvc;

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    // StaleSessionFilter (SecurityConfig) kiểm tra user của session qua service này; mock trả null = user hợp lệ
    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private DashboardService dashboardService;

    @BeforeEach
    void stubEmptyDashboard() {
        // Mặc định: chưa có số liệu (test cần dữ liệu cụ thể tự stub lại)
        when(dashboardService.getDashboard()).thenReturn(EMPTY_DASHBOARD);
    }

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void showsLastUpdatedTimeInServerTimeZone() throws Exception {
        // Job lưu tới micro giây (DATETIME(6)); HTML <time datetime> chỉ cho phép tối đa 3 chữ số lẻ → cắt về giây
        // 03:00:05 UTC = 10:00:05 giờ server (+07)
        Instant lastUpdatedAt = Instant.parse("2026-11-07T03:00:05.123456Z");
        when(dashboardService.getDashboard()).thenReturn(new DashboardResponse(lastUpdatedAt, null, SERVER_ZONE));

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lastUpdatedAt", lastUpdatedAt))
                .andExpect(content().string(containsString("Last updated time")))
                .andExpect(content().string(containsString(">2026-11-07 10:00:05 +07:00</time>")))
                // Giá trị máy đọc được (chuẩn HTML: offset dạng +07:00)
                .andExpect(content().string(containsString("datetime=\"2026-11-07T10:00:05+07:00\"")))
                // Trạng thái "chưa có" vẫn render (JS bật/tắt bằng hidden khi có event) nhưng đang ẩn
                .andExpect(content().string(containsString(
                        "<span id=\"last-updated-empty\" class=\"text-muted\" hidden=\"hidden\">Chưa cập nhật</span>")));
    }

    @Test
    void showsNotUpdatedYetWhenJobHasNotRunForUser() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(EMPTY_DASHBOARD);

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lastUpdatedAt", nullValue()))
                .andExpect(content().string(containsString(
                        "<span id=\"last-updated-empty\" class=\"text-muted\">Chưa cập nhật</span>")))
                .andExpect(content().string(containsString("<time id=\"last-updated-time\" hidden=\"hidden\"")));
    }

    @Test
    void showsImportStatistics() throws Exception {
        ImportStatisticsResponse statistics = new ImportStatisticsResponse(3, 1_250, 1_200, 50,
                Instant.parse("2026-11-07T02:30:15.987654Z"));
        when(dashboardService.getDashboard()).thenReturn(new DashboardResponse(null, statistics, SERVER_ZONE));

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("importStatistics", statistics))
                .andExpect(content().string(containsString("Import statistics")))
                .andExpect(content().string(containsString("id=\"stat-total-imports\">3</div>")))
                .andExpect(content().string(containsString("id=\"stat-success-rows\">1,200</div>")))
                .andExpect(content().string(containsString("id=\"stat-failed-rows\">50</div>")))
                .andExpect(content().string(containsString("datetime=\"2026-11-07T09:30:15+07:00\"")))
                .andExpect(content().string(containsString(">2026-11-07 09:30:15 +07:00</time>")))
                .andExpect(content().string(containsString(
                        "<div id=\"import-statistics-values\" class=\"row g-3 text-center\">")))
                .andExpect(content().string(containsString(
                        "<p id=\"import-statistics-empty\" class=\"mb-0 text-muted\" hidden=\"hidden\">")))
                // Comment trong template là comment parser-level của Thymeleaf, không lọt ra HTML
                .andExpect(content().string(not(containsString("IMPORT_COMPLETED"))));
    }

    @Test
    void showsNoImportYetWhenStatisticsAreMissing() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(EMPTY_DASHBOARD);

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("importStatistics", nullValue()))
                .andExpect(content().string(containsString(
                        "<p id=\"import-statistics-empty\" class=\"mb-0 text-muted\">Chưa có lần import nào</p>")))
                .andExpect(content().string(containsString(
                        "<div id=\"import-statistics-values\" class=\"row g-3 text-center\" hidden=\"hidden\">")));
    }

    @Test
    void pageWiresChartAndRealtimeScriptsWithContextRelativeUrls() throws Exception {
        String html = mockMvc.perform(get("/app/dashboard").contextPath("/app").with(oauth2Login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // URL cho dashboard.js theo context path (không hardcode domain / đường dẫn tuyệt đối)
        // Đích STOMP lấy từ hằng số StompDestinations (không hardcode trong JS); tên cookie CSRF cho lần kết nối lại
        assertThat(html).contains("data-metrics-destination=\"" + StompDestinations.USER_METRICS_DESTINATION + "\"",
                "<meta name=\"_csrf_cookie\" content=\"" + CsrfCookies.NAME + "\">");
        assertThat(html).contains("data-chart-data-url=\"/app/api/chart-data\"", "data-ws-url=\"/app/ws\"",
                "data-login-url=\"/app/login\"", "<canvas id=\"metrics-chart\"",
                "<script src=\"/app/js/csrf.js\"></script>", "<script src=\"/app/js/dashboard.js\"></script>");
        // Thư viện CDN pin version + SRI + crossorigin
        assertThat(CDN_SCRIPT.matcher(html).results().map(match -> match.group(1)))
                .containsExactlyInAnyOrder("bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js",
                        "chart.js@4.5.1/dist/chart.umd.min.js", "sockjs-client@1.6.1/dist/sockjs.min.js",
                        "@stomp/stompjs@7.3.0/bundles/stomp.umd.min.js");
        // CSP không có 'unsafe-inline': mọi <script> đều là file (không có script inline), không có style inline
        assertThat(html).doesNotContainPattern("<script(?![^>]*\\ssrc=)").doesNotContain("style=\"");
        // dashboard.js chạy sau csrf.js (dùng csrfHeaders() cho frame CONNECT)
        assertThat(html.indexOf("/js/csrf.js")).isLessThan(html.indexOf("/js/dashboard.js"));
        // Comment parser-level của Thymeleaf không lọt ra HTML
        assertThat(html).doesNotContain("bật/tắt bằng thuộc tính");
    }

    @Test
    void dashboardScriptNeverWritesHtmlFromServerData() throws Exception {
        String script = new ClassPathResource("static/js/dashboard.js").getContentAsString(StandardCharsets.UTF_8);

        // Dữ liệu API / payload WebSocket chỉ được ghi bằng textContent (OWASP A05 — XSS)
        assertThat(script.lines().filter(line -> !line.trim().startsWith("//")))
                .noneMatch(line -> line.contains("innerHTML") || line.contains("outerHTML")
                        || line.contains("insertAdjacentHTML") || line.contains("document.write"))
                .noneMatch(line -> line.contains("eval(") || line.contains("new Function"));
        // Chart tạo 1 lần, cập nhật bằng chart.update()
        assertThat(script).containsOnlyOnce("new window.Chart(").contains("chart.update()");
        // Đích STOMP / loại event không hardcode: lấy từ data-*, mọi event có type đều làm mới
        assertThat(script).doesNotContain("/topic/", "/queue/", "METRICS_UPDATED", "IMPORT_PROCESSED")
                .contains("root.dataset.metricsDestination");
        // CSRF đọc lại trước mỗi lần kết nối (cookie có thể đã đổi), không cố định lúc tạo client
        assertThat(script).contains("beforeConnect").doesNotContain("connectHeaders: window.csrfHeaders()");
    }

    @Test
    void sessionNotMappedToKnownUserIsLoggedOutAndSentToLogin() throws Exception {
        // Fail closed: không 500, không vòng lặp /login ↔ /dashboard
        when(dashboardService.getDashboard()).thenThrow(new AuthenticationRequiredException());
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/dashboard").session(session).with(oauth2Login()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void facebookUserSeesDashboardWithNameFromUserInfo() throws Exception {
        mockMvc.perform(get("/dashboard").with(oauth2Login()
                        .attributes(attrs -> {
                            attrs.put("id", "1234567890");
                            attrs.put("name", "Dev User");
                        })))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(content().string(containsString("Welcome, <span>Dev User</span>")))
                // Không hiển thị id phía provider thay cho tên
                .andExpect(content().string(not(containsString("1234567890"))))
                // Logout bằng form POST có hidden _csrf
                .andExpect(content().string(containsString("action=\"/logout\" method=\"post\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void nameFromProviderIsHtmlEscaped() throws Exception {
        mockMvc.perform(get("/dashboard").with(oauth2Login()
                        .attributes(attrs -> attrs.put("name", "<script>alert(1)</script>"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Test
    void userWithoutNameAttributeFallsBackToPrincipalName() throws Exception {
        // Provider không trả "name" → hiển thị tên principal (id phía provider) thay vì để trống
        OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "2244994945"), "id");
        mockMvc.perform(get("/dashboard").with(oauth2Login().oauth2User(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, <span>2244994945</span>")));
    }

    @Test
    void appPrincipalShowsDisplayNameStoredAtLogin() throws Exception {
        // X user không có "name": tên đã chuẩn hoá lúc đăng nhập là @username, không phải id số phía provider
        AppOAuth2User principal = new AppOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "2244994945", "username", "devuser"), "id", 7L, "devuser");
        mockMvc.perform(get("/dashboard").with(oauth2Login().oauth2User(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, <span>devuser</span>")))
                .andExpect(content().string(not(containsString("2244994945"))));
    }

    @Test
    void rootRedirectsToDashboard() throws Exception {
        mockMvc.perform(get("/").with(oauth2Login()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/dashboard"));
    }
}
