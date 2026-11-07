package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.AppProperties;
import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse.RowError;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.excel.GenericExcelImporter;
import com.huuhv.socialanalytics.repository.PostRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.huuhv.socialanalytics.excel.TestWorkbooks.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostImportServiceTest {

    private static final long OWNER_ID = 1L;
    private static final Object[] HEADER = {"Provider", "External ID", "URL", "Content", "Published At"};
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    @Mock
    private PostRepository postRepository;

    @Mock
    private CurrentUserService currentUserService;

    private PostImportService postImportService;

    private final User owner = User.builder().id(OWNER_ID).email("owner@example.com").displayName("Owner").build();

    @BeforeEach
    void setUp() {
        postImportService = service(3);
    }

    private PostImportService service(int maxRows) {
        Validator validator = VALIDATOR_FACTORY.getValidator();
        AppProperties properties = new AppProperties(
                new AppProperties.Pagination(20, 100),
                new AppProperties.ImportSettings(maxRows, DataSize.ofMegabytes(20)),
                new AppProperties.ExportSettings(10000, Duration.ZERO),
                // Service không dùng key mã hoá token, thread pool
                null, null);
        return new PostImportService(new GenericExcelImporter(), new PostUpsertService(postRepository),
                currentUserService, validator, properties);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void importsValidRowsAndReportsInvalidRowsWithRowNumber() {
        givenCurrentUser();
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/1", "Hello", LocalDateTime.of(2026, 1, 1, 9, 0)},
                new Object[]{"FACEBOOK", "bad id!", "", "x"},
                new Object[]{"TWITTER", "tw-1", "https://x.com/owner/status/1"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).extracting(RowError::row, RowError::column)
                .containsExactly(tuple(3, "External ID"), tuple(3, "URL"));

        List<Post> saved = captureSaved();
        assertThat(saved).extracting(Post::getExternalId).containsExactlyInAnyOrder("fb-1", "tw-1");
        assertThat(saved).allSatisfy(post -> assertThat(post.getUser()).isSameAs(owner));
        assertThat(saved).filteredOn(post -> post.getExternalId().equals("fb-1")).singleElement()
                .satisfies(post -> {
                    assertThat(post.getContent()).isEqualTo("Hello");
                    assertThat(post.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
                });
    }

    @Test
    void templateFileImportsWithoutErrors() throws Exception {
        givenCurrentUser();
        PostImportService defaultLimitService = service(1000);
        byte[] template;
        try (var in = defaultLimitService.getTemplate().getInputStream()) {
            template = in.readAllBytes();
        }

        ImportResultResponse result = defaultLimitService.importPosts(upload(template));

        assertThat(result.errors()).isEmpty();
        assertThat(result.totalRows()).isPositive().isEqualTo(result.created());
    }

    @Test
    void rejectsUrlOutsideProviderDomain() {
        givenCurrentUser();
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://evil.com/posts/1"},
                new Object[]{"TWITTER", "tw-1", "http://x.com/owner/status/1"},
                new Object[]{"TWITTER", "tw-2", "https://facebook.com/posts/2"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.errors()).extracting(RowError::row).containsExactly(2, 3, 4);
        assertThat(result.errors()).allSatisfy(error -> assertThat(error.column()).isEqualTo("URL"));
        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void reportsDuplicateWithinFileIgnoringCase() {
        givenCurrentUser();
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/1"},
                new Object[]{"FACEBOOK", "FB-1", "https://facebook.com/posts/1"},
                new Object[]{"TWITTER", "fb-1", "https://x.com/owner/status/1"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.errors()).containsExactly(new RowError(3, "External ID", "duplicate of row 2"));
    }

    @Test
    void updatesExistingPostOfCurrentUser() {
        givenCurrentUser();
        Post existing = Post.builder().id(10L).user(owner).provider(Provider.FACEBOOK).externalId("fb-1")
                .url("https://facebook.com/posts/old").content("old").build();
        when(postRepository.findByUserIdAndProviderAndExternalIdIn(eq(OWNER_ID), eq(Provider.FACEBOOK), anyCollection()))
                .thenReturn(List.of(existing));
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/new", "new"},
                new Object[]{"FACEBOOK", "fb-2", "https://facebook.com/posts/2"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(existing.getUrl()).isEqualTo("https://facebook.com/posts/new");
        assertThat(existing.getContent()).isEqualTo("new");
        assertThat(captureSaved()).hasSize(2).contains(existing);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(postRepository).findByUserIdAndProviderAndExternalIdIn(eq(OWNER_ID), eq(Provider.FACEBOOK), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("fb-1", "fb-2");
    }

    @Test
    void blankContentAndPublishedAtKeepExistingValues() {
        givenCurrentUser();
        LocalDateTime publishedAt = LocalDateTime.of(2025, 5, 1, 8, 0);
        Post existing = Post.builder().id(10L).user(owner).provider(Provider.FACEBOOK).externalId("fb-1")
                .url("https://facebook.com/posts/old").content("old content").publishedAt(publishedAt).build();
        when(postRepository.findByUserIdAndProviderAndExternalIdIn(eq(OWNER_ID), eq(Provider.FACEBOOK), anyCollection()))
                .thenReturn(List.of(existing));
        byte[] file = xlsx(HEADER, new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/fixed"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(existing.getUrl()).isEqualTo("https://facebook.com/posts/fixed");
        assertThat(existing.getContent()).isEqualTo("old content");
        assertThat(existing.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void reportsAllErrorsOfRowIncludingThoseAfterConversionError() {
        givenCurrentUser();
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://evil.com/posts/1", null, "01/01/2026"},
                new Object[]{"MYSPACE", "x".repeat(101), "https://facebook.com/posts/2"});

        ImportResultResponse result = postImportService.importPosts(upload(file));

        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.errors()).extracting(RowError::row, RowError::column).containsExactly(
                // Ngày sai định dạng không che mất lỗi URL ngoài whitelist
                tuple(2, "URL"), tuple(2, "Published At"),
                // Provider không đọc được → chỉ báo lỗi chuyển kiểu, không thêm "must not be null"
                tuple(3, "Provider"), tuple(3, "External ID"));
    }

    @Test
    void importRowCoversEveryPostRequestField() {
        // Import validate PostRequest dựng từ PostImportRow → mọi component phải có cột cùng tên field
        List<String> importFields = ExcelColumnMetadata.of(PostImportRow.class).stream()
                .map(meta -> meta.field().getName()).toList();

        assertThat(Arrays.stream(PostRequest.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactlyInAnyOrderElementsOf(importFields);
    }

    @Test
    void rejectsNonXlsxExtension() {
        givenCurrentUser();
        MockMultipartFile file = new MockMultipartFile("file", "posts.csv", "text/csv", "a,b".getBytes());

        assertThatThrownBy(() -> postImportService.importPosts(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only .xlsx files are accepted");
        verifyNoInteractions(postRepository);
    }

    @Test
    void rejectsXlsxNameWithNonExcelContent() {
        givenCurrentUser();
        MockMultipartFile file = new MockMultipartFile("file", "posts.xlsx", null, "Provider,URL\nFACEBOOK,x".getBytes());

        assertThatThrownBy(() -> postImportService.importPosts(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only .xlsx files are accepted");
        verifyNoInteractions(postRepository);
    }

    @Test
    void rejectsEmptyFile() {
        givenCurrentUser();
        MockMultipartFile file = new MockMultipartFile("file", "posts.xlsx", null, new byte[0]);

        assertThatThrownBy(() -> postImportService.importPosts(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("File is empty");
    }

    @Test
    void rejectsFileOverRowLimit() {
        givenCurrentUser();
        byte[] file = xlsx(HEADER,
                new Object[]{"FACEBOOK", "fb-1", "https://facebook.com/posts/1"},
                new Object[]{"FACEBOOK", "fb-2", "https://facebook.com/posts/2"},
                new Object[]{"FACEBOOK", "fb-3", "https://facebook.com/posts/3"},
                new Object[]{"FACEBOOK", "fb-4", "https://facebook.com/posts/4"});

        assertThatThrownBy(() -> postImportService.importPosts(upload(file)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("File exceeds the maximum of 3 data rows");
        verify(postRepository, never()).saveAll(any());
    }

    @Test
    void rejectsFileWithMissingColumns() {
        givenCurrentUser();
        byte[] file = xlsx(new Object[]{"Provider", "URL"}, new Object[]{"FACEBOOK", "https://facebook.com/posts/1"});

        assertThatThrownBy(() -> postImportService.importPosts(upload(file)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Missing columns: External ID, Content, Published At");
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeReadingFile() {
        when(currentUserService.getCurrentUser()).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(() -> postImportService.importPosts(upload(xlsx(HEADER))))
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(postRepository);
    }

    private void givenCurrentUser() {
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    private static MockMultipartFile upload(byte[] content) {
        return new MockMultipartFile("file", "posts.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    @SuppressWarnings("unchecked")
    private List<Post> captureSaved() {
        ArgumentCaptor<List<Post>> captor = ArgumentCaptor.forClass(List.class);
        verify(postRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
