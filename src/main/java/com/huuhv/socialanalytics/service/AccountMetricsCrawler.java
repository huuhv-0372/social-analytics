package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.social.InvalidAccessTokenException;
import com.huuhv.socialanalytics.client.social.PostMetrics;
import com.huuhv.socialanalytics.client.social.SocialPlatformClient;
import com.huuhv.socialanalytics.client.social.SocialPlatformClients;
import com.huuhv.socialanalytics.client.social.SocialPlatformException;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.PostRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Crawl metrics của <b>1 tài khoản</b> mạng xã hội trên thread pool {@code socialCrawlerExecutor} — đơn vị song song
 * của {@code UpdateSocialMetricsJob}. Tách bean riêng khỏi job: gọi {@code @Async} trong cùng class không đi qua proxy.
 * <p>
 * Không có {@code @Transactional}: đọc dữ liệu và ghi kết quả là 2 transaction ngắn ở Service khác, lời gọi API mạng xã
 * hội nằm giữa và <b>ngoài</b> transaction (không giữ connection DB trong lúc chờ mạng).
 * <p>
 * Mọi lỗi của tài khoản được xử lý tại đây (log + {@link CrawlResult.Status#FAILED}) để không ảnh hưởng tài khoản
 * khác. Không log access token, cũng không log id bài viết (dữ liệu người dùng nhập từ Excel) — chỉ log số lượng.
 * <p>
 * Hạn chót của lần chạy job được kiểm tra trước khi bắt đầu, trước mỗi lô gọi API và trước khi ghi: quá hạn → dừng,
 * bỏ kết quả ({@code FAILED}, không ghi gì) để task trễ không chồng lên lần chạy sau.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountMetricsCrawler {

    private final SocialAccountService socialAccountService;
    private final PostService postService;
    private final SocialMetricService socialMetricService;
    private final SocialPlatformClients socialPlatformClients;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * @param deadline hạn chót của lần chạy job — quá hạn thì không làm tiếp và không ghi kết quả
     */
    @Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)
    public CompletableFuture<CrawlResult> crawl(Long accountId, Instant deadline) {
        return CompletableFuture.completedFuture(crawlNow(accountId, deadline));
    }

    CrawlResult crawlNow(Long accountId, Instant deadline) {
        try {
            if (isPast(deadline)) {
                // Task nằm trong hàng đợi tới khi job đã quá hạn
                log.warn("Account {}: job deadline passed before crawling started; skipped", accountId);
                return CrawlResult.failed(accountId);
            }
            Optional<CrawlAccount> found = socialAccountService.findCrawlAccount(accountId);
            if (found.isEmpty()) {
                log.info("Skip account {}: no longer exists", accountId);
                return CrawlResult.skipped(accountId);
            }
            CrawlAccount account = found.get();
            // Kiểm tra hạn token trước khi gọi API (OWASP A07); so sánh theo UTC như lúc lưu
            if (!account.hasUsableToken(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)))) {
                log.warn("Skip {} account {}: access token expired or unreadable, user must log in again",
                        account.provider(), accountId);
                return CrawlResult.skipped(accountId);
            }
            return crawl(account, deadline);
        } catch (InvalidAccessTokenException e) {
            // Token bị thu hồi phía provider: lỗi nghiệp vụ, không cần stack trace
            log.warn("Account {}: access token rejected by provider, user must log in again", accountId);
            return CrawlResult.failed(accountId);
        } catch (SocialPlatformException e) {
            log.error("Account {}: social platform call failed", accountId, e);
            return CrawlResult.failed(accountId);
        } catch (RuntimeException e) {
            log.error("Account {}: crawling metrics failed", accountId, e);
            return CrawlResult.failed(accountId);
        }
    }

    private CrawlResult crawl(CrawlAccount account, Instant deadline) {
        List<PostRef> posts = postService.findPostRefs(account.userId(), account.provider());
        SocialPlatformClient client = socialPlatformClients.get(account.provider());
        int batchSize = appProperties.jobs().updateMetrics().batchSize();

        List<CrawledMetric> crawled = new ArrayList<>(posts.size());
        int rejected = 0;
        for (int from = 0; from < posts.size(); from += batchSize) {
            if (isPast(deadline)) {
                return deadlineExceeded(account.id());
            }
            List<PostRef> batch = posts.subList(from, Math.min(from + batchSize, posts.size()));
            List<PostMetrics> results = client.fetchMetrics(account.accessToken(),
                    batch.stream().map(PostRef::externalId).toList());
            rejected += collectValid(batch, results, crawled);
        }
        if (rejected > 0) {
            log.warn("Account {}: ignored {} invalid metric results from {}", account.id(), rejected,
                    account.provider());
        }

        // Tài khoản không có bài vẫn ghi last_updated_at: đã đồng bộ thành công, chỉ là không có gì để cập nhật.
        // Mọi mốc thời gian lưu DB theo giờ UTC (UtcDateTimes) — cắt về micro giây cho khớp DATETIME(6)
        if (isPast(deadline)) {
            return deadlineExceeded(account.id());
        }
        LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MICROS);
        // Bài bị xoá trong lúc crawl được bỏ qua khi ghi → số snapshot đã lưu có thể nhỏ hơn số kết quả
        int saved = socialMetricService.recordSnapshots(account.id(), crawled, nowUtc, nowUtc);
        log.info("Account {} ({}): saved {} snapshots for {} posts", account.id(), account.provider(),
                saved, posts.size());
        return CrawlResult.succeeded(account.id(), account.userId(), saved);
    }

    private boolean isPast(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private static CrawlResult deadlineExceeded(Long accountId) {
        log.warn("Account {}: job deadline passed while crawling; results discarded", accountId);
        return CrawlResult.failed(accountId);
    }

    /**
     * Dữ liệu từ API là không tin cậy (OWASP A08): chỉ nhận kết quả của bài vừa hỏi, mỗi bài 1 lần, số liệu không âm.
     *
     * @return số kết quả bị loại
     */
    private static int collectValid(List<PostRef> batch, List<PostMetrics> results, List<CrawledMetric> target) {
        Map<String, Long> postIdByExternalId = new HashMap<>();
        batch.forEach(post -> postIdByExternalId.put(post.externalId(), post.id()));
        Set<Long> seen = new HashSet<>();
        int rejected = 0;
        for (PostMetrics metrics : results) {
            Long postId = metrics == null ? null : postIdByExternalId.get(metrics.externalId());
            if (postId == null || !seen.add(postId) || hasNegative(metrics)) {
                rejected++;
                continue;
            }
            target.add(new CrawledMetric(postId, metrics.likes(), metrics.shares(), metrics.comments(),
                    metrics.followers()));
        }
        return rejected;
    }

    private static boolean hasNegative(PostMetrics metrics) {
        return metrics.likes() < 0 || metrics.shares() < 0 || metrics.comments() < 0 || metrics.followers() < 0;
    }
}
