package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.service.AppOAuth2User;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

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

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void showsLastUpdatedTimeInServerTimeZone() throws Exception {
        ZonedDateTime lastUpdatedAt = ZonedDateTime.of(2026, 11, 7, 10, 0, 5, 0, ZoneId.of("Asia/Ho_Chi_Minh"));
        when(dashboardService.findLastUpdatedAt()).thenReturn(Optional.of(lastUpdatedAt));

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lastUpdatedAt", lastUpdatedAt))
                .andExpect(content().string(containsString("Last updated time")))
                .andExpect(content().string(containsString(">2026-11-07 10:00:05 +07:00</time>")))
                // Giá trị máy đọc được (chuẩn HTML: offset dạng +07:00)
                .andExpect(content().string(containsString("datetime=\"2026-11-07T10:00:05+07:00\"")))
                .andExpect(content().string(not(containsString("Chưa cập nhật"))));
    }

    @Test
    void showsNotUpdatedYetWhenJobHasNotRunForUser() throws Exception {
        when(dashboardService.findLastUpdatedAt()).thenReturn(Optional.empty());

        mockMvc.perform(get("/dashboard").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lastUpdatedAt", nullValue()))
                .andExpect(content().string(containsString("Chưa cập nhật")))
                .andExpect(content().string(not(containsString("<time"))));
    }

    @Test
    void sessionNotMappedToKnownUserIsLoggedOutAndSentToLogin() throws Exception {
        // Fail closed: không 500, không vòng lặp /login ↔ /dashboard
        when(dashboardService.findLastUpdatedAt()).thenThrow(new AuthenticationRequiredException());
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
