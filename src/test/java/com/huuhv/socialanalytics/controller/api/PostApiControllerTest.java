package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import com.huuhv.socialanalytics.service.PostService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PostApiController.class)
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class PostApiControllerTest {

    private static final String VALID_BODY = """
            {"provider":"FACEBOOK","externalId":"fb-1","url":"https://facebook.com/posts/1","content":"Hello"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;

    // SecurityConfig cần bean này (oauth2Login → userInfoEndpoint); bản thật phụ thuộc DB
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @Test
    void listReturnsPage() throws Exception {
        when(postService.findAll(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(response(10L)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/posts").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10))
                .andExpect(jsonPath("$.content[0].externalId").value("fb-10"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listUsesDefaultSizeAndCapsSizeAtMax() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/posts").with(oauth2Login())).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts").param("size", "5000").with(oauth2Login())).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postService, times(2)).findAll(captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
    }

    @Test
    void listWithInvalidSortReturns400() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenThrow(new BadRequestException("Sort property is not allowed"));

        mockMvc.perform(get("/api/posts").param("sort", "user.email").with(oauth2Login()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // Deny-by-default: request ẩn danh bị filter chain chặn (401 ProblemDetail, không redirect), không tới Service
    @Test
    void requestWithoutAuthenticationReturns401BeforeReachingService() throws Exception {
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(postService);
    }

    @Test
    void nonApiPathRedirectsAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getOfOtherUsersPostReturns404() throws Exception {
        when(postService.findById(99L)).thenThrow(new ResourceNotFoundException("Post"));

        mockMvc.perform(get("/api/posts/99").with(oauth2Login()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Post not found"));
    }

    @Test
    void getWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/api/posts/abc").with(oauth2Login()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postService);
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(postService.create(any(PostRequest.class))).thenReturn(response(10L));

        mockMvc.perform(post("/api/posts").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/posts/10"))
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void createWithInvalidBodyReturns400WithFieldErrors() throws Exception {
        String body = """
                {"provider":null,"externalId":"bad id!","url":"","content":"x"}
                """;

        mockMvc.perform(post("/api/posts").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("provider", "externalId", "url")));
        verifyNoInteractions(postService);
    }

    @Test
    void createWithUnknownProviderReturns400() throws Exception {
        String body = VALID_BODY.replace("FACEBOOK", "MYSPACE");

        mockMvc.perform(post("/api/posts").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postService);
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(postService.create(any(PostRequest.class))).thenThrow(new DuplicateResourceException("Duplicate"));

        mockMvc.perform(post("/api/posts").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void updateReturnsUpdatedPost() throws Exception {
        when(postService.update(eq(10L), any(PostRequest.class))).thenReturn(response(10L));

        mockMvc.perform(put("/api/posts/10").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void updateWithInvalidBodyReturns400() throws Exception {
        String body = VALID_BODY.replace("\"fb-1\"", "\"\"");

        mockMvc.perform(put("/api/posts/10").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("externalId")));
        verifyNoInteractions(postService);
    }

    @Test
    void updateOfOtherUsersPostReturns404() throws Exception {
        when(postService.update(eq(99L), any(PostRequest.class))).thenThrow(new ResourceNotFoundException("Post"));

        mockMvc.perform(put("/api/posts/99").with(oauth2Login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/posts/10").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(postService).delete(10L);
    }

    @Test
    void deleteOfOtherUsersPostReturns404() throws Exception {
        doThrow(new ResourceNotFoundException("Post")).when(postService).delete(99L);

        mockMvc.perform(delete("/api/posts/99").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() throws Exception {
        when(postService.findById(10L)).thenThrow(new IllegalStateException("SQL: select * from users -- secret"));

        mockMvc.perform(get("/api/posts/10").with(oauth2Login()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void writeWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/api/posts").with(oauth2Login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/posts/10").with(oauth2Login()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postService);
    }

    @Test
    void writeWithInvalidCsrfTokenReturns403() throws Exception {
        mockMvc.perform(put("/api/posts/10").with(oauth2Login()).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postService);
    }

    // Context mới: post-processor csrf() ở test khác thay CsrfTokenRepository của filter dùng chung bằng bản test
    // (chỉ ghi cookie cho request có csrf()), nên cookie thật chỉ kiểm tra được trên context chưa bị thay
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void clientCanWriteByEchoingCsrfCookieInHeader() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        when(postService.create(any(PostRequest.class))).thenReturn(response(10L));

        Cookie csrfCookie = mockMvc.perform(get("/api/posts").with(oauth2Login()))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        // Luồng của Swagger UI / Postman / curl: gửi lại cookie và giá trị của nó qua header X-XSRF-TOKEN
        mockMvc.perform(post("/api/posts").with(oauth2Login())
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void accessDeniedFromServiceReturns403Not500() throws Exception {
        when(postService.findById(10L)).thenThrow(new AccessDeniedException("denied"));

        mockMvc.perform(get("/api/posts/10").with(oauth2Login()))
                .andExpect(status().isForbidden());
    }

    private static PostResponse response(Long id) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new PostResponse(id, Provider.FACEBOOK, "fb-" + id, "https://facebook.com/posts/" + id,
                "content", null, now, now);
    }
}
