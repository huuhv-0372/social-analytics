package com.huuhv.socialanalytics.service;

/**
 * Kết quả crawl metrics của 1 tài khoản.
 *
 * @param snapshots số snapshot đã lưu (0 khi không thành công)
 */
public record CrawlResult(Long accountId, Status status, int snapshots) {

    public enum Status {
        /** Đã lưu snapshot và cập nhật {@code last_updated_at}. */
        SUCCEEDED,
        /** Không crawl: tài khoản không còn, token hết hạn / không đọc được (user cần đăng nhập lại). */
        SKIPPED,
        /** Lỗi khi gọi API hoặc khi lưu — dữ liệu không đổi, lần chạy sau thử lại. */
        FAILED
    }

    public static CrawlResult succeeded(Long accountId, int snapshots) {
        return new CrawlResult(accountId, Status.SUCCEEDED, snapshots);
    }

    public static CrawlResult skipped(Long accountId) {
        return new CrawlResult(accountId, Status.SKIPPED, 0);
    }

    public static CrawlResult failed(Long accountId) {
        return new CrawlResult(accountId, Status.FAILED, 0);
    }
}
