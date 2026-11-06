package com.huuhv.socialanalytics.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void replacesEachCrAndLfSoInjectedLinesStayVisible() {
        assertThat(LogSanitizer.stripLineBreaks("/api/posts\r\nINFO Login succeeded: user=admin"))
                .isEqualTo("/api/posts__INFO Login succeeded: user=admin")
                .doesNotContain("\r", "\n");
    }

    @Test
    void keepsOrdinaryValueUnchanged() {
        assertThat(LogSanitizer.stripLineBreaks("access_denied")).isEqualTo("access_denied");
        assertThat(LogSanitizer.stripLineBreaks("")).isEmpty();
    }

    @Test
    void nullIsPrintedAsNull() {
        assertThat(LogSanitizer.stripLineBreaks(null)).isEqualTo("null");
    }
}
