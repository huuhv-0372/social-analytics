package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostReportServiceTest {

    private static final long OWNER_ID = 1L;
    private static final int MAX_ROWS = 2;
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 1, 9, 0);
    // Clock cố định (UTC+7) → tên file xác định được, theo đúng múi giờ của Clock chứ không theo máy chạy test
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T01:30:05Z"), ZoneOffset.ofHours(7));

    @Mock
    private PostService postService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private UserRateLimiter userRateLimiter;

    private PostReportService postReportService;

    private final User owner = User.builder().id(OWNER_ID).email("owner@example.com").displayName("Owner").build();

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Pagination(20, 100),
                new AppProperties.ImportSettings(1000, DataSize.ofMegabytes(20)),
                new AppProperties.ExportSettings(MAX_ROWS, MIN_INTERVAL),
                // Service không dùng key mã hoá token, thread pool, job
                null, null, null);
        postReportService = new PostReportService(postService, currentUserService, userRateLimiter,
                new GenericExcelExporter(), properties, CLOCK);
    }

    @Test
    void exportsReportOfCurrentUserAsXlsx() throws Exception {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        // Lấy dư 1 dòng (max + 1) để phát hiện vượt giới hạn
        when(postService.findReportRows(OWNER_ID, MAX_ROWS + 1)).thenReturn(List.of(row("fb-1", 10L), row("fb-2", null)));

        ExportFile file = postReportService.exportReport();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.content().writeTo(out);

        assertThat(file.filename()).isEqualTo("posts-report-20260315-083005.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Posts");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Provider");
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("Metrics Collected At");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("fb-1");
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(10);
            // Bài chưa có snapshot → ô metric trống
            assertThat(sheet.getRow(2).getCell(5)).isNull();
        }
    }

    @Test
    void checksRateLimitBeforeQuerying() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(postService.findReportRows(OWNER_ID, MAX_ROWS + 1)).thenReturn(List.of());

        postReportService.exportReport();

        InOrder order = inOrder(userRateLimiter, postService);
        order.verify(userRateLimiter).acquire(PostReportService.RATE_LIMIT_ACTION, OWNER_ID, MIN_INTERVAL);
        order.verify(postService).findReportRows(OWNER_ID, MAX_ROWS + 1);
    }

    @Test
    void rateLimitedRequestIsRejectedBeforeQuerying() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        doThrow(new TooManyRequestsException(Duration.ofSeconds(3)))
                .when(userRateLimiter).acquire(PostReportService.RATE_LIMIT_ACTION, OWNER_ID, MIN_INTERVAL);

        assertThatThrownBy(() -> postReportService.exportReport()).isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(postService);
    }

    @Test
    void rejectsReportOverRowLimit() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(postService.findReportRows(OWNER_ID, MAX_ROWS + 1)).thenReturn(List.of(
                row("fb-1", 1L), row("fb-2", 2L), row("fb-3", 3L)));

        assertThatThrownBy(() -> postReportService.exportReport())
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Report exceeds the maximum of 2 rows");
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeQuerying() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(() -> postReportService.exportReport())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(postService, userRateLimiter);
    }

    private static PostReportRow row(String externalId, Long likes) {
        return new PostReportRow(Provider.FACEBOOK, externalId, "https://facebook.com/posts/" + externalId, "content",
                AT, likes, likes == null ? null : 1L, likes == null ? null : 2L, likes == null ? null : 3L,
                likes == null ? null : AT.plusDays(1));
    }
}
