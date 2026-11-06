package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PostRequestTest {

    @Test
    void truncatesPublishedAtToSecondsSoExcelRoundTripKeepsValue() {
        PostRequest request = new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", null,
                LocalDateTime.of(2026, 1, 1, 10, 0, 0, 123_456_000));

        assertThat(request.publishedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
    }

    @Test
    void keepsNullPublishedAt() {
        PostRequest request = new PostRequest(Provider.FACEBOOK, "fb-1", "https://facebook.com/posts/1", null, null);

        assertThat(request.publishedAt()).isNull();
    }
}
