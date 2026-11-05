package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.config.SecurityConfig;
import com.huuhv.socialanalytics.config.WebConfig;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Test
    void listReturnsPage() throws Exception {
        when(postService.findAll(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(response(10L)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/posts").with(user("dev@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10))
                .andExpect(jsonPath("$.content[0].externalId").value("fb-10"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listUsesDefaultSizeAndCapsSizeAtMax() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/posts").with(user("dev@example.com"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts").param("size", "5000").with(user("dev@example.com"))).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postService, times(2)).findAll(captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
    }

    @Test
    void listWithInvalidSortReturns400() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenThrow(new BadRequestException("Sort property is not allowed"));

        mockMvc.perform(get("/api/posts").param("sort", "user.email").with(user("dev@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requestWithoutAuthenticationReturns401() throws Exception {
        when(postService.findAll(any(Pageable.class))).thenThrow(new AuthenticationRequiredException());

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void nonApiPathIsDeniedByDefault() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(302, 401));
    }

    @Test
    void getOfOtherUsersPostReturns404() throws Exception {
        when(postService.findById(99L)).thenThrow(new ResourceNotFoundException("Post"));

        mockMvc.perform(get("/api/posts/99").with(user("dev@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Post not found"));
    }

    @Test
    void getWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/api/posts/abc").with(user("dev@example.com")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postService);
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(postService.create(any(PostRequest.class))).thenReturn(response(10L));

        mockMvc.perform(post("/api/posts").with(user("dev@example.com")).with(csrf())
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

        mockMvc.perform(post("/api/posts").with(user("dev@example.com")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("provider", "externalId", "url")));
        verifyNoInteractions(postService);
    }

    @Test
    void createWithUnknownProviderReturns400() throws Exception {
        String body = VALID_BODY.replace("FACEBOOK", "MYSPACE");

        mockMvc.perform(post("/api/posts").with(user("dev@example.com")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postService);
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(postService.create(any(PostRequest.class))).thenThrow(new DuplicateResourceException("Duplicate"));

        mockMvc.perform(post("/api/posts").with(user("dev@example.com")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void updateReturnsUpdatedPost() throws Exception {
        when(postService.update(eq(10L), any(PostRequest.class))).thenReturn(response(10L));

        mockMvc.perform(put("/api/posts/10").with(user("dev@example.com")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/posts/10").with(user("dev@example.com")).with(csrf()))
                .andExpect(status().isNoContent());
        verify(postService).delete(10L);
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() throws Exception {
        when(postService.findById(10L)).thenThrow(new IllegalStateException("SQL: select * from users -- secret"));

        mockMvc.perform(get("/api/posts/10").with(user("dev@example.com")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void writeWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/api/posts").with(user("dev@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/posts/10").with(user("dev@example.com")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postService);
    }

    @Test
    void writeWithInvalidCsrfTokenReturns403() throws Exception {
        mockMvc.perform(put("/api/posts/10").with(user("dev@example.com")).with(csrf().useInvalidToken())
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

        Cookie csrfCookie = mockMvc.perform(get("/api/posts").with(user("dev@example.com")))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        // Luồng của Swagger UI / Postman / curl: gửi lại cookie và giá trị của nó qua header X-XSRF-TOKEN
        mockMvc.perform(post("/api/posts").with(user("dev@example.com"))
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void accessDeniedFromServiceReturns403Not500() throws Exception {
        when(postService.findById(10L)).thenThrow(new AccessDeniedException("denied"));

        mockMvc.perform(get("/api/posts/10").with(user("dev@example.com")))
                .andExpect(status().isForbidden());
    }

    private static PostResponse response(Long id) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new PostResponse(id, Provider.FACEBOOK, "fb-" + id, "https://facebook.com/posts/" + id,
                "content", null, now, now);
    }
}
