package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SocialMetricReportServiceTest {

    private static final long OWNER_ID = 1L;
    private static final int MAX_ROWS = 2;
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    // Server chạy UTC+7: tên file + ô Collected At theo múi giờ này, DB lưu giờ UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T01:30:05Z"), ZoneOffset.ofHours(7));

    @Mock
    private SocialMetricService socialMetricService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private UserRateLimiter userRateLimiter;

    private SocialMetricReportService service;

    private final User owner = User.builder().id(OWNER_ID).displayName("Owner").build();

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.ImportSettings(1000, DataSize.ofMegabytes(20), Duration.ZERO),
                new AppProperties.ExportSettings(MAX_ROWS, MIN_INTERVAL),
                null, null, null, null, null, null);
        service = new SocialMetricReportService(socialMetricService, currentUserService, userRateLimiter,
                new GenericExcelExporter(), properties, CLOCK);
    }

    @Test
    void exportsSnapshotsInServerTimeZone() throws Exception {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(socialMetricService.findReportRows(OWNER_ID, LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0), MAX_ROWS + 1))
                .thenReturn(List.of(new SocialMetricReportRow(Provider.FACEBOOK, "fb-1", 10L, 2L, 3L, 400L,
                        LocalDateTime.of(2026, 1, 5, 9, 0))));

        ExportFile file = service.exportMetrics(from, to);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.content().writeTo(out);

        assertThat(file.filename()).isEqualTo("metrics-report-20260315-083005.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Metrics");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Provider");
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo("Collected At");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("fb-1");
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(400);
            // 09:00 UTC → 16:00 giờ server (+07)
            assertThat(sheet.getRow(1).getCell(6).getLocalDateTimeCellValue())
                    .isEqualTo(LocalDateTime.of(2026, 1, 5, 16, 0));
        }
    }

    @Test
    void checksRateLimitBeforeQueryingWithOwnBucket() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(socialMetricService.findReportRows(OWNER_ID, null, null, MAX_ROWS + 1)).thenReturn(List.of());

        service.exportMetrics(null, null);

        InOrder order = inOrder(userRateLimiter, socialMetricService);
        order.verify(socialMetricService).countReportRowsUpTo(OWNER_ID, null, null, MAX_ROWS + 1);
        // Bucket riêng "export-metrics": export bài viết không làm chặn export metric và ngược lại
        order.verify(userRateLimiter).acquire("export-metrics", OWNER_ID, MIN_INTERVAL);
        order.verify(socialMetricService).findReportRows(OWNER_ID, null, null, MAX_ROWS + 1);
    }

    @Test
    void rejectsInvertedRangeBeforeAnything() {
        assertThatThrownBy(() -> service.exportMetrics(Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("from must not be after to");
        verifyNoInteractions(currentUserService, userRateLimiter, socialMetricService);
    }

    /** Snapshot mới được ghi giữa câu đếm và câu lấy dữ liệu → vẫn chặn theo số dòng thực lấy được. */
    @Test
    void rejectsExportOverRowLimit() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        SocialMetricReportRow row = new SocialMetricReportRow(Provider.TWITTER, "tw-1", 1L, 1L, 1L, 1L,
                LocalDateTime.of(2026, 1, 1, 0, 0));
        when(socialMetricService.findReportRows(OWNER_ID, null, null, MAX_ROWS + 1)).thenReturn(List.of(row, row, row));

        assertThatThrownBy(() -> service.exportMetrics(null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Report exceeds the maximum of 2 rows");
    }

    @Test
    void rateLimitedOrAnonymousRequestIsRejectedBeforeQuerying() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException()).thenReturn(owner);
        doThrow(new TooManyRequestsException(Duration.ofSeconds(3)))
                .when(userRateLimiter).acquire("export-metrics", OWNER_ID, MIN_INTERVAL);

        assertThatThrownBy(() -> service.exportMetrics(null, null))
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(socialMetricService);
        assertThatThrownBy(() -> service.exportMetrics(null, null)).isInstanceOf(TooManyRequestsException.class);
        // Chỉ câu đếm rẻ chạy trước khi tính tần suất; query dữ liệu (filesort) không chạy
        verify(socialMetricService, never()).findReportRows(any(), any(), any(), anyInt());
    }

    /**
     * Khoảng quá rộng → 400 mà KHÔNG tốn lượt export: user thu hẹp from/to rồi gửi lại ngay không bị 429; query dữ
     * liệu (có filesort) không chạy.
     */
    @Test
    void rowLimitIsCheckedBeforeConsumingRateLimit() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(socialMetricService.countReportRowsUpTo(OWNER_ID, null, null, MAX_ROWS + 1)).thenReturn(MAX_ROWS + 1L);

        assertThatThrownBy(() -> service.exportMetrics(null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Report exceeds the maximum of 2 rows");
        verifyNoInteractions(userRateLimiter);
        verify(socialMetricService, never()).findReportRows(any(), any(), any(), anyInt());
    }
}
