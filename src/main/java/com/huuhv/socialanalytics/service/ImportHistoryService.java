package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lịch sử import Excel ({@code import_histories}). Là nơi <b>duy nhất</b> đọc/ghi bảng này — mọi quy tắc chuyển trạng
 * thái ({@code PENDING → PROCESSED / DEAD_LETTERED}) nằm ở đây; service khác (vd {@link ImportStatisticsService}) gọi
 * qua service này, không inject {@link ImportHistoryRepository}.
 */
@Service
@RequiredArgsConstructor
public class ImportHistoryService {

    private final ImportHistoryRepository importHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Ghi 1 lần import (trạng thái {@link ImportStatus#PENDING}) và phát {@link ImportCompletedEvent}.
     * <p>
     * {@code MANDATORY}: phải chạy trong transaction ghi bài viết của import — lịch sử và bài viết cùng commit/rollback,
     * và sự kiện {@code @TransactionalEventListener(AFTER_COMMIT)} chỉ được phát khi có transaction (không có thì
     * Spring lặng lẽ bỏ sự kiện → mất message).
     *
     * Số dòng do caller tính ({@link PostUpsertService#saveImport}); DB kiểm tra
     * {@code total_rows = created_rows + updated_rows + failed_rows} (CHECK constraint).
     *
     * @return {@code importId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(User owner, int totalRows, int createdRows, int updatedRows, int failedRows) {
        ImportHistory history = importHistoryRepository.save(ImportHistory.builder()
                .user(owner)
                .totalRows(totalRows)
                .createdRows(createdRows)
                .updatedRows(updatedRows)
                .failedRows(failedRows)
                .status(ImportStatus.PENDING)
                .build());
        eventPublisher.publishEvent(new ImportCompletedEvent(history.getId(), owner.getId(), totalRows,
                createdRows + updatedRows, failedRows, UtcDateTimes.toInstant(history.getCreatedAt())));
        return history.getId();
    }

    /**
     * Đánh dấu {@link ImportStatus#PROCESSED} nếu chưa xử lý ({@code PENDING}, hoặc {@code DEAD_LETTERED} khi message
     * được gửi lại sau khi vào DLQ) — cơ sở idempotency của listener {@code IMPORT_COMPLETED}: UPDATE giữ khoá dòng tới
     * hết transaction, message trùng xử lý song song phải chờ rồi thấy đã {@code PROCESSED}.
     * <p>
     * {@code MANDATORY}: chạy chung transaction với bước cộng thống kê ({@link ImportStatisticsService#applyImport}) —
     * cộng lỗi thì trạng thái cũng rollback, message được giao lại.
     *
     * @param processedAt giờ UTC ghi vào {@code processed_at} / {@code updated_at}
     * @return {@code true} nếu vừa đánh dấu; {@code false} nếu đã {@code PROCESSED} hoặc không tồn tại
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(long importId, LocalDateTime processedAt) {
        return importHistoryRepository.markProcessed(importId, processedAt) == 1;
    }

    /**
     * Message {@code IMPORT_COMPLETED} của lần import đã vào DLQ → {@link ImportStatus#DEAD_LETTERED} (chỉ từ
     * {@code PENDING}; đã {@code PROCESSED} thì giữ nguyên) để biết lần import nào chưa được cộng vào thống kê.
     *
     * @return {@code true} nếu vừa đánh dấu; {@code false} nếu không ở {@code PENDING} hoặc không tồn tại
     */
    @Transactional
    public boolean markDeadLettered(long importId) {
        return importHistoryRepository.markDeadLettered(importId, now()) == 1;
    }

    /** Số liệu + owner (lưu trong DB) của 1 lần import — projection, không nạp entity. */
    @Transactional(readOnly = true)
    public Optional<ImportCounts> findCounts(long importId) {
        return importHistoryRepository.findCountsById(importId);
    }

    @Transactional(readOnly = true)
    public boolean exists(long importId) {
        return importHistoryRepository.existsById(importId);
    }

    /**
     * Id các import còn {@code PENDING} lâu hơn {@code minAge} (tính từ lần ghi/gửi gần nhất) — message
     * {@code IMPORT_COMPLETED} của chúng có thể đã mất (producer gửi lỗi, broker không lưu message). Lâu chưa thử nhất
     * trước.
     * <p>
     * Import {@code DEAD_LETTERED} không được gửi lại tự động (message lỗi mãi sẽ lặp DLQ → gửi lại → DLQ...). Sau khi
     * khắc phục nguyên nhân, đưa về {@code PENDING} bằng tay ({@code UPDATE import_histories SET status = 'PENDING'
     * WHERE status = 'DEAD_LETTERED'}) thì job sẽ gửi lại ở lần chạy sau.
     */
    @Transactional(readOnly = true)
    public List<Long> findPendingIdsToResend(Duration minAge, int limit) {
        return importHistoryRepository.findPendingIdsUpdatedBefore(now().minus(minAge), Limit.of(limit));
    }

    /**
     * Gửi lại message {@code IMPORT_COMPLETED} cho 1 import còn {@code PENDING}: dời {@code updated_at} (lần gửi sau
     * phải chờ thêm {@code min-age}) rồi phát lại {@link ImportCompletedEvent} — producer gửi message sau khi
     * transaction này commit, giống luồng import gốc. Số liệu lấy từ DB. Listener idempotent theo {@code importId} nên
     * message gốc còn nằm trong queue thì cũng chỉ được cộng 1 lần.
     *
     * @return {@code true} nếu đã phát lại; {@code false} nếu import không còn {@code PENDING} (đã được xử lý trong lúc
     *         chờ) hoặc không tồn tại
     */
    @Transactional
    public boolean republishPending(long importId) {
        if (importHistoryRepository.touchPending(importId, now()) == 0) {
            return false;
        }
        // Dòng vừa UPDATE ở trên chắc chắn tồn tại và đang bị khoá bởi transaction này
        ImportCounts counts = importHistoryRepository.findCountsById(importId).orElseThrow();
        eventPublisher.publishEvent(new ImportCompletedEvent(importId, counts.userId(), counts.totalRows(),
                counts.successRows(), counts.failedRows(), UtcDateTimes.toInstant(counts.importedAt())));
        return true;
    }

    private LocalDateTime now() {
        return UtcDateTimes.toUtc(clock.instant());
    }
}
