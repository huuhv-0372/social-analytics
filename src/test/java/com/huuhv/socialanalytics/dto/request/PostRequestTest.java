package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PostRequestTest {

    @Test
    void truncatesPublishedAtToSecondsSoExcelRoundTripKeepsValue() {
        PostRequest request = new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", null,
                Instant.parse("2026-01-01T10:00:00.123456Z"));

        assertThat(request.publishedAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void keepsNullPublishedAt() {
        PostRequest request = new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", null, null);

        assertThat(request.publishedAt()).isNull();
    }
}
