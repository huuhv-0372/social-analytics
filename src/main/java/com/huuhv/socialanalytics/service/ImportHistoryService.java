package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.ImportStatus;
import com.huuhv.socialanalytics.repository.ImportHistoryRepository;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lịch sử import Excel ({@code import_histories}).
 */
@Service
@RequiredArgsConstructor
public class ImportHistoryService {

    private final ImportHistoryRepository importHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Ghi 1 lần import (trạng thái {@link ImportStatus#PENDING}) và phát {@link ImportCompletedEvent}.
     * <p>
     * {@code MANDATORY}: phải chạy trong transaction ghi bài viết của import — lịch sử và bài viết cùng commit/rollback,
     * và sự kiện {@code @TransactionalEventListener(AFTER_COMMIT)} chỉ được phát khi có transaction (không có thì
     * Spring lặng lẽ bỏ sự kiện → mất message).
     *
     * @return {@code importId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(User owner, int totalRows, int createdRows, int updatedRows) {
        int failedRows = totalRows - createdRows - updatedRows;
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
}
