package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailResponsesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void writesProblemDetailWithSameFieldsAsApiExceptionHandler() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemDetailResponses.write(response, HttpStatus.UNAUTHORIZED, "Authentication required");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        // Spring bỏ "type" mặc định (about:blank) giống body do Spring MVC ghi — RFC 9457: thiếu type = about:blank
        assertThat(body.has("type") ? body.get("type").asString() : "about:blank").isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("detail").asString()).isEqualTo("Authentication required");
        // Trường rỗng không xuất hiện (mixin của Spring), giống body do Spring MVC ghi
        assertThat(body.has("instance")).isFalse();
        assertThat(body.has("properties")).isFalse();
    }

    @Test
    void detailWithJsonSpecialCharactersIsEscaped() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String detail = "quote \" backslash \\ newline \n end";

        ProblemDetailResponses.write(response, HttpStatus.FORBIDDEN, detail);

        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("status").asInt()).isEqualTo(403);
    }
}
