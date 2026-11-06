package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.controller.web.LoginController;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cấu hình filter chain ở {@link SecurityConfig}: deny-by-default, entry point theo loại request, CSRF, logout, header.
 */
@WebMvcTest(LoginController.class)
@Import({SecurityConfig.class, WebConfig.class, CustomOAuth2UserService.class})
@ActiveProfiles("test")
class SecurityConfigWebTest {

    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    // Interceptor /api/** trong WebConfig cần bean này
    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void loginPageIsPublicAndExposesCsrfMetaTags() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"_csrf\" content=\"")))
                // Tên header kiểm ở maskedTokenFromMetaTagIsAcceptedInHeader (context chưa bị csrf() thay repository)
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"")));
    }

    @Test
    void loginPageShowsFixedMessagesWithoutEchoingParameters() throws Exception {
        mockMvc.perform(get("/login").param("error", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in failed")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(content().string(containsString("You have been signed out")));
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void loginPageOffersFacebookAndXLogin() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/facebook\"")))
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/twitter\"")));
    }

    @Test
    void authenticatedUserVisitingLoginIsRedirectedToDashboard() throws Exception {
        mockMvc.perform(get("/login").with(oauth2Login()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(SecurityConfig.LOGIN_SUCCESS_URL));
    }

    @Test
    void facebookAuthorizationRequestRedirectsToProviderWithStateAndCallbackUri() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/facebook"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location)
                .startsWith("https://www.facebook.com/")
                .contains("response_type=code")
                .contains("client_id=test-facebook-client-id")
                .contains("scope=public_profile%20email")
                .contains("state=")
                .contains("redirect_uri=http://localhost/login/oauth2/code/facebook")
                // PKCE mặc định cho cả confidential client (Spring Security 7)
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                // Client secret không bao giờ nằm trong URL trình duyệt
                .doesNotContain("test-facebook-client-secret");
    }

    @Test
    void xAuthorizationRequestUsesCustomProviderWithPkce() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/twitter"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location)
                .startsWith("https://x.com/i/oauth2/authorize?")
                .contains("response_type=code")
                .contains("client_id=test-twitter-client-id")
                .contains("scope=users.read%20tweet.read")
                .contains("state=")
                .contains("redirect_uri=http://localhost/login/oauth2/code/twitter")
                // X bắt buộc PKCE; chỉ chấp nhận S256 thay vì plain
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .doesNotContain("test-twitter-client-secret");
    }

    @Test
    void callbackWithoutMatchingAuthorizationRequestRedirectsToLoginError() throws Exception {
        // Callback giả mạo / state không khớp (không có authorization request đã lưu trong session) → từ chối
        mockMvc.perform(get("/login/oauth2/code/facebook").param("code", "forged").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(SecurityConfig.LOGIN_FAILURE_URL));
    }

    @Test
    void staticResourcesArePublic() throws Exception {
        mockMvc.perform(get("/js/csrf.js")).andExpect(status().isOk());
    }

    @Test
    void anonymousPageRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void apiDocsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // TẠM THỜI tới Task 3.4: Swagger UI nhận 401 Basic (trình duyệt hiện hộp thoại đăng nhập) thay vì redirect /login
    @Test
    void swaggerUiRequiresAuthenticationViaBasicChallenge() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Basic")));
    }

    @Test
    void wrongCredentialsReturn401ProblemDetailWithoutDetails() throws Exception {
        mockMvc.perform(get("/api/posts").with(httpBasic("dev@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    @Test
    void logoutWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/logout").with(user("dev@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutByPostRedirectsToFixedLoginUrl() throws Exception {
        mockMvc.perform(post("/logout").param("redirect", "https://evil.example").with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(SecurityConfig.LOGOUT_SUCCESS_URL))
                .andExpect(unauthenticated());
    }

    @Test
    void logoutByGetIsNotSupported() throws Exception {
        // Không có handler GET /logout → 404, user vẫn đăng nhập (không bị CSRF logout qua link/ảnh)
        mockMvc.perform(get("/logout").with(user("dev@example.com")))
                .andExpect(status().isNotFound());
    }

    // Context mới: post-processor csrf() ở test khác thay CsrfTokenRepository bằng bản test
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void maskedTokenFromMetaTagIsAcceptedInHeader() throws Exception {
        MvcResult page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-XSRF-TOKEN\"")))
                .andReturn();
        Cookie csrfCookie = page.getResponse().getCookie("XSRF-TOKEN");
        Matcher meta = CSRF_META.matcher(page.getResponse().getContentAsString());
        assertThat(csrfCookie).isNotNull();
        assertThat(meta.find()).isTrue();
        // Token trong meta là bản đã mask (BREACH) — khác giá trị raw trong cookie
        assertThat(meta.group(1)).isNotEqualTo(csrfCookie.getValue());

        // Luồng fetch của trang Thymeleaf: header lấy từ <meta name="_csrf">
        mockMvc.perform(post("/logout").with(user("dev@example.com"))
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", meta.group(1)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(SecurityConfig.LOGOUT_SUCCESS_URL));
    }
}
