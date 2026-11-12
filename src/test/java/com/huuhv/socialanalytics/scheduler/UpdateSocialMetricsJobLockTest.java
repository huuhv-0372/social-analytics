package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.CryptoConfig;
import com.huuhv.socialanalytics.config.SchedulerLockConfig;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.AccountMetricsCrawler;
import com.huuhv.socialanalytics.service.SocialAccountService;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2 "instance" của job dùng chung bảng {@code scheduler_locks} trên MySQL Testcontainers (mỗi instance 1
 * {@code LockProvider} với {@code locked_by} riêng, giống 2 tiến trình): mỗi lịch chỉ 1 instance chạy.
 * Cùng cấu hình slice với các test repository (dùng chung context + container); tắt transaction của test để khoá được
 * commit thật và instance kia nhìn thấy.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CryptoConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UpdateSocialMetricsJobLockTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void releaseLocks() {
        jdbcTemplate.update("DELETE FROM scheduler_locks");
    }

    @Test
    void whileOneInstanceRunsTheOtherSkips() throws Exception {
        Instance first = new Instance("instance-a", Duration.ofMinutes(1), Duration.ZERO);
        Instance second = new Instance("instance-b", Duration.ofMinutes(1), Duration.ZERO);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(first.accounts.findAllAccountIds()).thenAnswer(invocation -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });

        CompletableFuture<Optional<UpdateSocialMetricsJob.Summary>> firstRun =
                CompletableFuture.supplyAsync(first.job::runExclusively);
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(second.job.runExclusively()).isEmpty();
        verifyNoInteractions(second.accounts, second.crawler);
        assertThat(lockedBy()).isEqualTo("instance-a");

        release.countDown();
        assertThat(firstRun.get(10, TimeUnit.SECONDS)).isPresent();
        // lock-at-least-for = 0 → khoá nhả ngay khi xong, instance kia chạy được
        when(second.accounts.findAllAccountIds()).thenReturn(List.of());
        assertThat(second.job.runExclusively()).isPresent();
    }

    @Test
    void lockIsKeptForLockAtLeastForAfterQuickRun() {
        // Job xong ngay nhưng instance có đồng hồ lệch vài giây kích hoạt sau đó vẫn không được chạy lại
        Instance first = new Instance("instance-a", Duration.ofMinutes(1), Duration.ofMinutes(1));
        Instance second = new Instance("instance-b", Duration.ofMinutes(1), Duration.ofMinutes(1));
        when(first.accounts.findAllAccountIds()).thenReturn(List.of());

        assertThat(first.job.runExclusively()).isPresent();

        assertThat(second.job.runExclusively()).isEmpty();
        verifyNoInteractions(second.accounts, second.crawler);
    }

    @Test
    void lockOfCrashedInstanceExpiresAfterLockAtMostFor() {
        Duration lockAtMostFor = Duration.ofSeconds(2);
        Instance survivor = new Instance("instance-b", lockAtMostFor, Duration.ZERO);
        when(survivor.accounts.findAllAccountIds()).thenReturn(List.of());
        // Instance A lấy khoá rồi chết — không bao giờ nhả
        assertThat(SchedulerLockConfig.lockProvider(dataSource, "instance-a").lock(new LockConfiguration(
                Instant.now(), UpdateSocialMetricsJob.LOCK_NAME, lockAtMostFor, Duration.ZERO))).isPresent();

        assertThat(survivor.job.runExclusively()).isEmpty();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> survivor.job.runExclusively().isPresent());
        assertThat(lockedBy()).isEqualTo("instance-b");
    }

    private String lockedBy() {
        return jdbcTemplate.queryForObject("SELECT locked_by FROM scheduler_locks WHERE name = ?", String.class,
                UpdateSocialMetricsJob.LOCK_NAME);
    }

    /** 1 tiến trình app: job + LockProvider riêng (locked_by riêng), service mock để biết job có chạy hay không. */
    private final class Instance {

        private final SocialAccountService accounts = mock(SocialAccountService.class);
        private final AccountMetricsCrawler crawler = mock(AccountMetricsCrawler.class);
        private final UpdateSocialMetricsJob job;

        private Instance(String lockedBy, Duration lockAtMostFor, Duration lockAtLeastFor) {
            LockProvider lockProvider = SchedulerLockConfig.lockProvider(dataSource, lockedBy);
            AppProperties properties = new AppProperties(null, null, null, null, null, new AppProperties.Jobs(
                    new AppProperties.UpdateMetrics(100, TIMEOUT, lockAtMostFor, lockAtLeastFor)), null);
            job = new UpdateSocialMetricsJob(accounts, crawler, properties, Clock.systemUTC(),
                    new DefaultLockingTaskExecutor(lockProvider));
        }
    }
}
