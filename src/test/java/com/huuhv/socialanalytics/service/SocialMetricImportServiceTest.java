package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.request.SocialMetricImportRow;
import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse.RowError;
import com.huuhv.socialanalytics.dto.response.MetricImportResultResponse;
import com.huuhv.socialanalytics.dto.response.PostRef;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.excel.GenericExcelImporter;
import com.huuhv.socialanalytics.excel.TestWorkbooks.Formula;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static com.huuhv.socialanalytics.excel.TestWorkbooks.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialMetricImportServiceTest {

    private static final long OWNER_ID = 1L;
    private static final Object[] HEADER =
            {"Provider", "External ID", "Likes", "Shares", "Comments", "Followers", "Collected At"};
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    // Server chạy UTC+7: ô ngày giờ Excel hiểu theo múi giờ này
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T01:30:05Z"), ZoneOffset.ofHours(7));
    private static final LocalDateTime AT = LocalDateTime.of(2026, 3, 1, 9, 0);

    @Mock
    private PostService postService;

    @Mock
    private SocialMetricService socialMetricService;

    @Mock
    private CurrentUserService currentUserService;

    @Captor
    private ArgumentCaptor<List<SocialMetricRequest>> saved;

    private SocialMetricImportService service;

    private final User owner = User.builder().id(OWNER_ID).displayName("Owner").build();

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(new AppProperties.ImportSettings(10, DataSize.ofMegabytes(20)),
                new AppProperties.ExportSettings(10000, Duration.ZERO), null, null, null, null, null, null);
        service = new SocialMetricImportService(new ExcelImportSupport(new GenericExcelImporter(),
                VALIDATOR_FACTORY.getValidator(), properties), postService, socialMetricService, currentUserService,
                CLOCK);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "metrics.xlsx", "application/octet-stream", content);
    }

    private void givenOwnedPosts() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        // Tra bài theo lô — chỉ bài của owner (bài của user khác không bao giờ được trả về)
        when(postService.findOwnedPostRefs(eq(OWNER_ID), eq(Provider.FACEBOOK), anyCollection()))
                .thenReturn(List.of(new PostRef(10L, "fb-1"), new PostRef(11L, "fb-2")));
        when(socialMetricService.importSnapshots(eq(OWNER_ID), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
    }

    @Test
    void importsValidRowsAndReportsInvalidRowsByColumn() {
        givenOwnedPosts();
        when(postService.findOwnedPostRefs(eq(OWNER_ID), eq(Provider.TWITTER), anyCollection())).thenReturn(List.of());
        byte[] content = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", 10, 2, 3, 400, AT},
                // Bài không có trong các bài của owner (không tồn tại hoặc của user khác)
                new Object[]{"TWITTER", "tw-404", 1, 1, 1, 1, AT},
                new Object[]{"FACEBOOK", "fb-1", -1, 2, 3, 400, AT.plusHours(1)},
                // Trùng bài (không phân biệt hoa thường như unique index) + cùng thời điểm với dòng 2
                new Object[]{"FACEBOOK", "FB-1", 11, 2, 3, 400, AT},
                new Object[]{"MYSPACE", "x-1", 1, 1, 1, 1, AT},
                new Object[]{"FACEBOOK", "fb-2", new Formula("1+1"), 1, 1, 1, LocalDateTime.of(2099, 1, 1, 0, 0)},
                new Object[]{null, " ", 1, 1, 1, 1, AT});

        MetricImportResultResponse result = service.importMetrics(file(content));

        assertThat(result.totalRows()).isEqualTo(7);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(6);
        assertThat(result.errors()).extracting(RowError::row, RowError::column, RowError::message).containsExactly(
                tuple(3, "External ID", "post not found"),
                tuple(4, "Likes", "must be greater than or equal to 0"),
                tuple(5, "External ID", "duplicate of row 2"),
                tuple(6, "Provider", "must be one of FACEBOOK, TWITTER"),
                // Công thức bị từ chối, không thêm "must not be null" trùng lặp cho cùng cột
                tuple(7, "Likes", "formulas are not allowed"),
                tuple(7, "Collected At", "must be a date in the past or in the present"),
                tuple(8, "Provider", "must not be null"),
                tuple(8, "External ID", "must not be blank"));
        verify(socialMetricService).importSnapshots(eq(OWNER_ID), saved.capture());
        // 09:00 giờ server (+07) = 02:00 UTC
        assertThat(saved.getValue()).containsExactly(new SocialMetricRequest(10L, 10L, 2L, 3L, 400L,
                Instant.parse("2026-03-01T02:00:00Z")));
    }

    @Test
    void exportedFileImportsBackUnchanged() throws Exception {
        givenOwnedPosts();
        List<SocialMetricReportRow> exported = List.of(
                new SocialMetricReportRow(Provider.FACEBOOK, "fb-1", 10L, 2L, 3L, 400L, AT),
                new SocialMetricReportRow(Provider.FACEBOOK, "fb-2", 9_007_199_254_740_993L, 0L, 0L, 1L,
                        AT.plusMinutes(30)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GenericExcelExporter().write(exported, SocialMetricReportRow.class, "Metrics", out);

        MetricImportResultResponse result = service.importMetrics(file(out.toByteArray()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).isEqualTo(2);
        verify(socialMetricService).importSnapshots(eq(OWNER_ID), saved.capture());
        assertThat(saved.getValue()).containsExactly(
                new SocialMetricRequest(10L, 10L, 2L, 3L, 400L, Instant.parse("2026-03-01T02:00:00Z")),
                // Long vượt 2^53 được exporter ghi dạng text → đọc lại đủ chữ số
                new SocialMetricRequest(11L, 9_007_199_254_740_993L, 0L, 0L, 1L,
                        Instant.parse("2026-03-01T02:30:00Z")));
    }

    /**
     * File mẫu tham chiếu các bài demo-* của file mẫu import bài viết: user đã import file đó thì import nguyên file mẫu
     * metric phải thành công (danh sách bài lấy từ chính posts-import-template.xlsx → 2 file không lệch nhau).
     */
    @Test
    void templateImportsWithoutErrorsAfterPostsTemplate() throws Exception {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        List<PostImportRow> templatePosts;
        try (InputStream in = new ClassPathResource("excel/posts-import-template.xlsx").getInputStream()) {
            templatePosts = new GenericExcelImporter().read(in, PostImportRow.class, 1000, 20L * 1024 * 1024)
                    .rows().stream().map(ExcelRow::value).toList();
        }
        when(postService.findOwnedPostRefs(eq(OWNER_ID), any(Provider.class), anyCollection())).thenAnswer(call -> {
            Provider provider = call.getArgument(1);
            Collection<String> wanted = call.getArgument(2);
            return IntStream.range(0, templatePosts.size())
                    .filter(i -> templatePosts.get(i).getProvider() == provider
                            && wanted.contains(templatePosts.get(i).getExternalId()))
                    .mapToObj(i -> new PostRef(100L + i, templatePosts.get(i).getExternalId()))
                    .toList();
        });
        when(socialMetricService.importSnapshots(eq(OWNER_ID), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
        byte[] template;
        try (InputStream in = service.getTemplate().getInputStream()) {
            template = in.readAllBytes();
        }

        MetricImportResultResponse result = service.importMetrics(file(template));

        assertThat(result.errors()).isEmpty();
        assertThat(result.totalRows()).isEqualTo(8);
        assertThat(result.imported()).isEqualTo(8);
        verify(socialMetricService).importSnapshots(eq(OWNER_ID), saved.capture());
        // Mọi bài demo-* của file mẫu bài viết đều có snapshot; ô ngày dạng chuỗi cũng đọc được
        assertThat(saved.getValue()).extracting(SocialMetricRequest::postId).containsOnly(100L, 101L, 102L, 103L)
                .contains(100L, 101L, 102L, 103L);
        assertThat(saved.getValue()).extracting(SocialMetricRequest::collectedAt)
                .contains(LocalDateTime.of(2026, 6, 3, 10, 0).atZone(CLOCK.getZone()).toInstant());
    }

    @Test
    void looksUpPostsInBatchPerProvider() {
        givenOwnedPosts();
        byte[] content = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", 1, 1, 1, 1, AT},
                new Object[]{"FACEBOOK", "fb-2", 1, 1, 1, 1, AT},
                new Object[]{"FACEBOOK", "fb-1", 1, 1, 1, 1, AT.plusHours(1)});

        service.importMetrics(file(content));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> externalIds = ArgumentCaptor.forClass(Collection.class);
        verify(postService).findOwnedPostRefs(eq(OWNER_ID), eq(Provider.FACEBOOK), externalIds.capture());
        assertThat(externalIds.getValue()).containsExactly("fb-1", "fb-2");
    }

    @Test
    void headerOnlyFileIsRejectedWithoutWriting() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        assertThatThrownBy(() -> service.importMetrics(file(xlsx(HEADER))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("File has no data rows");
        verifyNoInteractions(socialMetricService, postService);
    }

    @Test
    void rejectsNonXlsxAndMissingColumns() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        assertThatThrownBy(() -> service.importMetrics(new MockMultipartFile("file", "metrics.csv", "text/csv",
                "a,b".getBytes()))).isInstanceOf(BadRequestException.class)
                .hasMessage("Only .xlsx files are accepted");
        // File import bài viết (thiếu cột metric) → lỗi cả file
        assertThatThrownBy(() -> service.importMetrics(file(xlsx(new Object[]{"Provider", "External ID", "URL"},
                new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/1"}))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Missing columns: Likes");
        verifyNoInteractions(socialMetricService, postService);
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeParsing() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(() -> service.importMetrics(file(new byte[]{1, 2, 3})))
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(socialMetricService, postService);
    }

    @Test
    void importRowCoversEverySnapshotFieldOfRequest() {
        // Mọi component (trừ postId — Service tra từ Provider + External ID) phải có cột cùng tên field
        List<String> importFields = ExcelColumnMetadata.of(SocialMetricImportRow.class).stream()
                .map(ExcelColumnMetadata.ColumnMeta::property).toList();

        assertThat(Arrays.stream(SocialMetricRequest.class.getRecordComponents()).map(RecordComponent::getName)
                .filter(name -> !name.equals("postId")).toList()).isSubsetOf(importFields);
        // Cột export trùng cột import → file export import lại được
        assertThat(ExcelColumnMetadata.of(SocialMetricReportRow.class)).extracting(ExcelColumnMetadata.ColumnMeta::name)
                .containsExactlyElementsOf(ExcelColumnMetadata.of(SocialMetricImportRow.class).stream()
                        .map(ExcelColumnMetadata.ColumnMeta::name).toList());
    }
}
