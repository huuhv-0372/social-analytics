package com.huuhv.socialanalytics.config;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.Utils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Khoá phân tán cho job {@code @Scheduled} (ShedLock trên MySQL, bảng {@code scheduler_locks} — Flyway {@code V4_1}):
 * chạy nhiều instance thì mỗi lịch chỉ 1 instance chạy job. Job gọi {@link LockingTaskExecutor} trực tiếp (không dùng
 * {@code @SchedulerLock}/AOP của {@code shedlock-spring}) để thời hạn khoá đọc từ {@code AppProperties} (kiểu
 * {@code Duration}, có validate) và chỉ đường chạy theo lịch mới lấy khoá.
 * <ul>
 *     <li>{@code usingDbTime()}: thời điểm khoá/hết hạn tính bằng {@code UTC_TIMESTAMP(3)} của MySQL — không phụ thuộc
 *     đồng hồ từng instance.</li>
 *     <li>{@code locked_by} = hostname + id ngẫu nhiên của tiến trình: 2 instance trên cùng máy không nhả nhầm khoá của
 *     nhau (lệnh unlock của ShedLock lọc theo {@code locked_by}).</li>
 *     <li>Transaction riêng của ShedLock ({@code DataSourceTransactionManager} tạo bên trong) — không tham gia
 *     transaction JPA nào.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerLockConfig {

    static final String LOCK_TABLE = "scheduler_locks";

    @Bean
    LockProvider schedulerLockProvider(DataSource dataSource) {
        return lockProvider(dataSource, Utils.getHostname() + "/" + UUID.randomUUID());
    }

    @Bean
    LockingTaskExecutor schedulerLockingTaskExecutor(LockProvider schedulerLockProvider) {
        return new DefaultLockingTaskExecutor(schedulerLockProvider);
    }

    /** Dùng chung cho test giả lập nhiều instance (mỗi instance 1 {@code lockedBy}). */
    public static LockProvider lockProvider(DataSource dataSource, String lockedBy) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .withLockedByValue(lockedBy)
                .usingDbTime()
                .build());
    }
}
