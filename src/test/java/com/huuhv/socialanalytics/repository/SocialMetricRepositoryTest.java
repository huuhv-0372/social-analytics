package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SocialMetricRepositoryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private SocialMetricRepository socialMetricRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private Post post;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().email("owner@example.com").displayName("Owner").build());
        post = postRepository.save(Post.builder()
                .user(owner)
                .provider(Provider.TWITTER)
                .externalId("tw-1")
                .url("https://x.com/owner/status/1")
                .build());
        // Mỗi lần crawl là 1 snapshot mới, lưu không theo thứ tự thời gian
        saveSnapshot(BASE.plusHours(2), 30);
        saveSnapshot(BASE, 10);
        saveSnapshot(BASE.plusHours(1), 20);
    }

    @Test
    void findTopReturnsLatestSnapshot() {
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(post.getId()))
                .get()
                .extracting(SocialMetric::getLikes)
                .isEqualTo(30L);
    }

    @Test
    void findTopReturnsEmptyWhenNoSnapshot() {
        assertThat(socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(-1L)).isEmpty();
    }

    @Test
    void searchWithoutRangeReturnsAllSnapshotsPaged() {
        Page<SocialMetric> page = socialMetricRepository.search(post.getId(), null, null,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "collectedAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(SocialMetric::getLikes).containsExactly(30L, 20L);
    }

    @Test
    void searchAppliesOptionalBounds() {
        Sort ascending = Sort.by("collectedAt");

        assertThat(socialMetricRepository.search(post.getId(), BASE.plusHours(1), null, PageRequest.of(0, 10, ascending)))
                .extracting(SocialMetric::getLikes).containsExactly(20L, 30L);
        assertThat(socialMetricRepository.search(post.getId(), null, BASE.plusHours(1), PageRequest.of(0, 10, ascending)))
                .extracting(SocialMetric::getLikes).containsExactly(10L, 20L);
    }

    @Test
    void reportRowsListOwnSnapshotsSortedByPostThenTimeWithinRange() {
        Post facebook = postRepository.save(Post.builder().user(owner).provider(Provider.FACEBOOK)
                .externalId("fb-1").url("https://facebook.com/posts/1").build());
        saveSnapshot(facebook, BASE.plusHours(1), 7);
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());
        Post othersPost = postRepository.save(Post.builder().user(other).provider(Provider.FACEBOOK)
                .externalId("fb-1").url("https://facebook.com/posts/1").build());
        saveSnapshot(othersPost, BASE.plusHours(1), 999);

        List<SocialMetricReportRow> all = socialMetricRepository.findReportRows(owner.getId(), null, null,
                Limit.unlimited());

        // FACEBOOK trước TWITTER (thứ tự enum lưu chuỗi → theo chữ cái), trong 1 bài theo thời gian; không có bài user khác
        assertThat(all).extracting(SocialMetricReportRow::provider, SocialMetricReportRow::likes,
                SocialMetricReportRow::collectedAt).containsExactly(
                tuple(Provider.FACEBOOK, 7L, BASE.plusHours(1)),
                tuple(Provider.TWITTER, 10L, BASE),
                tuple(Provider.TWITTER, 20L, BASE.plusHours(1)),
                tuple(Provider.TWITTER, 30L, BASE.plusHours(2)));
        assertThat(all.get(1)).isEqualTo(new SocialMetricReportRow(Provider.TWITTER, "tw-1", 10L, 1L, 2L, 1000L, BASE));
        // 2 đầu khoảng tính cả
        assertThat(socialMetricRepository.findReportRows(owner.getId(), BASE.plusHours(1), BASE.plusHours(1),
                Limit.unlimited())).extracting(SocialMetricReportRow::likes).containsExactly(7L, 20L);
        assertThat(socialMetricRepository.findReportRows(owner.getId(), null, null, Limit.of(2)))
                .extracting(SocialMetricReportRow::likes).containsExactly(7L, 10L);
    }

    /** Đếm có chặn trên, cùng điều kiện lọc với findReportRows (user, 2 đầu khoảng tính cả) — chạy trên MySQL. */
    @Test
    void countReportRowsStopsAtMaxCountAndMatchesReportFilter() {
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());
        Post othersPost = postRepository.save(Post.builder().user(other).provider(Provider.TWITTER)
                .externalId("tw-1").url("https://x.com/other/status/1").build());
        saveSnapshot(othersPost, BASE.plusHours(1), 999);
        entityManager.flush();

        assertThat(socialMetricRepository.countReportRowsUpTo(owner.getId(), null, null, 100)).isEqualTo(3);
        // Dừng ở maxCount (service truyền max-rows + 1 để biết có vượt hay không)
        assertThat(socialMetricRepository.countReportRowsUpTo(owner.getId(), null, null, 2)).isEqualTo(2);
        assertThat(socialMetricRepository.countReportRowsUpTo(owner.getId(), BASE.plusHours(1), BASE.plusHours(2),
                100)).isEqualTo(2);
        assertThat(socialMetricRepository.countReportRowsUpTo(owner.getId(), BASE.plusHours(3), null, 100)).isZero();
    }

    /**
     * Snapshot trùng = cùng bài + collected_at trong [from, to): DB lưu micro giây, file Excel chỉ có mili giây → khoảng
     * 1ms vẫn nhận ra snapshot đã có. Bài khác / ngoài khoảng không tính.
     */
    @Test
    void existingSnapshotPositionsMatchPostAndMillisecondWindow() {
        saveSnapshot(BASE.plusMinutes(5).plusNanos(123_456_000), 1);
        entityManager.flush();

        List<Number> positions = socialMetricRepository.findExistingSnapshotPositions("["
                + window(post.getId(), BASE.plusMinutes(5).plusNanos(123_000_000)) + ","
                + window(post.getId(), BASE.plusMinutes(6)) + ","
                + window(post.getId() + 1000, BASE) + ","
                + window(post.getId(), BASE) + "]");

        assertThat(positions).extracting(Number::intValue).containsExactlyInAnyOrder(1, 4);
    }

    private static String window(Long postId, LocalDateTime from) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        return "{\"postId\":" + postId + ",\"from\":\"" + format.format(from) + "\",\"to\":\""
                + format.format(from.plusNanos(1_000_000)) + "\"}";
    }

    @Test
    void findByIdAndPostUserIdReturnsEmptyForOtherUser() {
        SocialMetric metric = saveSnapshot(BASE.plusHours(3), 40);
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());

        assertThat(socialMetricRepository.findByIdAndPostUserId(metric.getId(), owner.getId())).isPresent();
        assertThat(socialMetricRepository.findByIdAndPostUserId(metric.getId(), other.getId())).isEmpty();
    }

    @Test
    void insertAllWritesEverySnapshotAcrossMultipleStatements() {
        Post second = postRepository.save(Post.builder()
                .user(owner)
                .provider(Provider.TWITTER)
                .externalId("tw-2")
                .url("https://x.com/owner/status/2")
                .build());
        LocalDateTime collectedAt = LocalDateTime.of(2026, 11, 10, 3, 0, 0, 123_000_000);
        // Nhiều hơn 1 câu INSERT (ROWS_PER_STATEMENT dòng/câu) để kiểm tra cả phần chia lô; mỗi snapshot 1 thời điểm
        // riêng (unique (post_id, collected_at)), likes = số giây lệch so với collectedAt
        int count = SocialMetricRepositoryImpl.ROWS_PER_STATEMENT + 1;
        List<SocialMetric> snapshots = IntStream.range(0, count)
                .mapToObj(i -> SocialMetric.builder()
                        .post(i % 2 == 0 ? post : second)
                        .likes(i)
                        .shares(i + 1)
                        .comments(i + 2)
                        .followers(i + 3)
                        .collectedAt(collectedAt.plusSeconds(i))
                        .build())
                .toList();

        assertThat(socialMetricRepository.insertAll(snapshots)).isEqualTo(count);
        entityManager.clear();

        assertThat(socialMetricRepository.search(second.getId(), null, null, PageRequest.of(0, 1000)))
                .hasSize(count / 2)
                .allSatisfy(metric -> {
                    assertThat(metric.getShares()).isEqualTo(metric.getLikes() + 1);
                    assertThat(metric.getComments()).isEqualTo(metric.getLikes() + 2);
                    assertThat(metric.getFollowers()).isEqualTo(metric.getLikes() + 3);
                    assertThat(metric.getCollectedAt()).isEqualTo(collectedAt.plusSeconds(metric.getLikes()));
                });
        // 3 snapshot của setUp + phần mới
        assertThat(socialMetricRepository.search(post.getId(), null, null, PageRequest.of(0, 1000)))
                .hasSize(3 + count - count / 2);
    }

    @Test
    void insertAllOfNothingWritesNothing() {
        assertThat(socialMetricRepository.insertAll(List.of())).isZero();
        assertThat(socialMetricRepository.count()).isEqualTo(3);
    }

    @Test
    void perDayQueryAddsLatestSnapshotOfEachPostBeforeEveryCutoffInOneQuery() {
        Post secondTweet = savePost(owner, Provider.TWITTER, "tw-2");
        Post facebookPost = savePost(owner, Provider.FACEBOOK, "fb-1");
        saveSnapshot(secondTweet, BASE.plusMinutes(90), 100);
        saveSnapshot(facebookPost, BASE.plusMinutes(30), 7);

        // Mốc 1 (BASE + 1h): snapshot đúng bằng mốc (likes 20) không được tính; tw-2 chưa có snapshot trước mốc.
        // Mốc 2 (BASE + 3h): fb-1 không có snapshot mới → giữ giá trị cũ (carry-forward); 2 tweet cộng lại.
        assertThat(rows(owner, BASE.plusHours(1), BASE.plusHours(3))).containsExactly(
                "1 FACEBOOK 7 0 1 1000",
                "1 TWITTER 10 1 2 1000",
                "2 FACEBOOK 7 0 1 1000",
                "2 TWITTER 130 13 26 1000");
    }

    @Test
    void followersComeFromMostRecentSnapshotOfProviderNotFromLargestOrSum() {
        Post secondTweet = savePost(owner, Provider.TWITTER, "tw-2");
        // tw-1: snapshot mới nhất lúc BASE + 2h, tác giả có 1000 followers. tw-2 crawl sau (BASE + 2h30) — tác giả đã
        // giảm còn 900: lấy 900 (MAX sẽ ra 1000 cũ, SUM sẽ đếm lặp thành 1900)
        saveSnapshot(secondTweet, BASE.plusMinutes(150), 100, 900);

        assertThat(rows(owner, BASE.plusHours(3))).containsExactly("1 TWITTER 130 13 26 900");
    }

    @Test
    void cutoffBeforeFirstSnapshotHasNoRow() {
        assertThat(rows(owner, BASE, BASE.plusHours(3))).containsExactly("2 TWITTER 30 3 6 1000");
    }

    @Test
    void perDayQueryExcludesPostsOfOtherUsers() {
        User other = userRepository.save(User.builder().email("other@example.com").displayName("Other").build());
        saveSnapshot(savePost(other, Provider.TWITTER, "tw-1"), BASE, 5000, 7000);

        assertThat(rows(owner, BASE.plusHours(3))).containsExactly("1 TWITTER 30 3 6 1000");
        assertThat(rows(other, BASE.plusHours(3))).containsExactly("1 TWITTER 5000 500 1000 7000");
    }

    /**
     * Mỗi bài tối đa 1 snapshot tại 1 thời điểm (Flyway V8): lớp chặn cuối khi 2 request / 2 instance cùng qua bước
     * kiểm tra trùng ở Service rồi cùng ghi. Bài khác cùng thời điểm vẫn ghi được.
     */
    @Test
    void uniqueIndexRejectsSecondSnapshotOfPostAtSameTime() {
        Post other = savePost(owner, Provider.FACEBOOK, "fb-1");
        saveSnapshot(other, BASE.plusHours(2), 1);

        assertThatThrownBy(() -> saveSnapshot(BASE.plusHours(2), 35))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Ghi theo lô (job crawl, import Excel) cũng bị chặn → Service rollback cả lô, đổi thành 409. */
    @Test
    void insertAllRejectsSnapshotOfPostAtExistingTime() {
        List<SocialMetric> duplicate = List.of(SocialMetric.builder()
                .post(post).likes(35).collectedAt(BASE.plusHours(2)).build());

        assertThatThrownBy(() -> socialMetricRepository.insertAll(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByPostIdAndCollectedAtMatchesExactTime() {
        assertThat(socialMetricRepository.existsByPostIdAndCollectedAt(post.getId(), BASE.plusHours(2))).isTrue();
        assertThat(socialMetricRepository.existsByPostIdAndCollectedAt(post.getId(), BASE.plusHours(2).plusNanos(1_000_000)))
                .isFalse();
        assertThat(socialMetricRepository.existsByPostIdAndCollectedAt(-1L, BASE.plusHours(2))).isFalse();
    }

    @Test
    void cutoffsKeepMicrosecondPrecision() {
        // Mốc truyền qua JSON tới DATETIME(6): snapshot sớm hơn mốc 1 micro giây vẫn được tính
        LocalDateTime cutoff = BASE.plusHours(5);
        saveSnapshot(cutoff.minusNanos(1_000), 50);

        assertThat(rows(owner, cutoff)).containsExactly("1 TWITTER 50 5 10 1000");
    }

    /** Kết quả dạng {@code "dayIndex PROVIDER likes shares comments followers"} để so sánh gọn. */
    private List<String> rows(User user, LocalDateTime... cutoffs) {
        return socialMetricRepository.sumLatestByProviderPerDay(user.getId(), json(cutoffs)).stream()
                .map(row -> row.getDayIndex() + " " + row.getProvider() + " " + row.getLikes() + " "
                        + row.getShares() + " " + row.getComments() + " " + row.getFollowers())
                .toList();
    }

    /** Cùng định dạng {@code SocialMetricService} dựng cho JSON_TABLE. */
    private static String json(LocalDateTime... cutoffs) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        return Arrays.stream(cutoffs).map(cutoff -> "\"" + format.format(cutoff) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private Post savePost(User user, Provider provider, String externalId) {
        String url = provider == Provider.FACEBOOK
                ? "https://facebook.com/posts/" + externalId
                : "https://x.com/owner/status/" + externalId;
        return postRepository.save(Post.builder()
                .user(user)
                .provider(provider)
                .externalId(externalId)
                .url(url)
                .build());
    }

    private SocialMetric saveSnapshot(LocalDateTime collectedAt, long likes) {
        return saveSnapshot(post, collectedAt, likes);
    }

    private SocialMetric saveSnapshot(Post target, LocalDateTime collectedAt, long likes) {
        return saveSnapshot(target, collectedAt, likes, 1000);
    }

    private SocialMetric saveSnapshot(Post target, LocalDateTime collectedAt, long likes, long followers) {
        return socialMetricRepository.save(SocialMetric.builder()
                .post(target)
                .likes(likes)
                .shares(likes / 10)
                .comments(likes / 5)
                .followers(followers)
                .collectedAt(collectedAt)
                .build());
    }
}
