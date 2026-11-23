package com.huuhv.socialanalytics.config;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pool đủ max thread và hàng đợi đầy → thread gửi task <b>chờ</b> tới khi hàng đợi có chỗ (backpressure), không làm mất
 * task và không tự chạy task như {@link ThreadPoolExecutor.CallerRunsPolicy}:
 * <ul>
 *     <li>Thread gửi (scheduler) chỉ điều phối nên timeout của job đo được trọn vẹn — không bị kẹt chạy crawl thay
 *     pool.</li>
 *     <li>Executor đã tắt → ném {@link RejectedExecutionException} (Spring đổi thành {@code TaskRejectedException}) để
 *     caller biết task không chạy. {@code CallerRunsPolicy} lặng lẽ bỏ task trong trường hợp này → future của
 *     {@code @Async} không bao giờ hoàn thành.</li>
 * </ul>
 * Thời gian chờ không giới hạn cứng: hàng đợi có chỗ khi 1 task bắt đầu chạy, mỗi task tự kết thúc theo timeout của
 * client mạng xã hội và hạn chót của job. Kiểm tra trạng thái tắt sau mỗi {@link #RECHECK_INTERVAL}.
 */
final class WaitForQueueSpacePolicy implements RejectedExecutionHandler {

    static final Duration RECHECK_INTERVAL = Duration.ofMillis(200);

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        try {
            while (!executor.isShutdown()) {
                if (executor.getQueue().offer(task, RECHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
                    // Executor tắt đúng lúc task vào hàng đợi: worker có thể đã thoát → gỡ ra, báo từ chối.
                    // remove() thất bại = worker đã lấy task → task vẫn chạy bình thường
                    if (executor.isShutdown() && executor.remove(task)) {
                        throw new RejectedExecutionException("Executor has been shut down");
                    }
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while waiting for space in the executor queue", e);
        }
        throw new RejectedExecutionException("Executor has been shut down");
    }
}
