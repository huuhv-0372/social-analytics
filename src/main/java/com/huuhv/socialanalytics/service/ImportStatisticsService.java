package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.repository.UserImportStatisticRepository;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Thống kê import tổng hợp theo user ({@code user_import_statistics}), cập nhật bất đồng bộ từ message
 * {@code IMPORT_COMPLETED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportStatisticsService {

    // Trạng thái import_histories chỉ đổi qua ImportHistoryService (quy tắc chuyển trạng thái nằm 1 chỗ)
    private final ImportHistoryService importHistoryService;
    private final UserImportStatisticRepository userImportStatisticRepository;
    private final DashboardEventService dashboardEventService;
    private final Clock clock;

    /**
     * Cộng 1 lần import vào thống kê của chủ sở hữu — <b>idempotent</b> theo {@code importId}: nhận lại cùng message
     * (redelivery, gửi trùng) không cộng 2 lần.
     * <p>
     * 1 transaction: đánh dấu lịch sử {@code PROCESSED} bằng UPDATE có điều kiện (0 dòng = đã xử lý / không tồn tại →
     * bỏ qua) rồi cộng thống kê. Lỗi ở bước cộng → rollback cả 2, lịch sử giữ trạng thái cũ → message được giao lại và
     * xử lý lại từ đầu (hết số lần giao → DLQ). Lần import {@code DEAD_LETTERED} được cộng khi message được gửi lại
     * sau khi khắc phục lỗi.
     * <p>
     * Số liệu và owner lấy từ {@code import_histories} trong DB, không lấy từ payload message (OWASP A08 — payload là
     * dữ liệu không tin cậy).
     *
     * @param reportedUserId {@code userId} ghi trong message — chỉ để đối chiếu, lệch với DB thì log cảnh báo
     */
    @Transactional
    public ApplyResult applyImport(long importId, Long reportedUserId) {
        LocalDateTime now = UtcDateTimes.toUtc(clock.instant());
        if (!importHistoryService.markProcessed(importId, now)) {
            return importHistoryService.exists(importId) ? ApplyResult.ALREADY_PROCESSED : ApplyResult.NOT_FOUND;
        }
        // Dòng vừa UPDATE ở trên chắc chắn tồn tại và đang bị khoá bởi transaction này
        ImportCounts counts = importHistoryService.findCounts(importId).orElseThrow();
        if (!Objects.equals(counts.userId(), reportedUserId)) {
            log.warn("Import {} message reports user {} but history belongs to user {}; using stored owner",
                    importId, reportedUserId, counts.userId());
        }
        userImportStatisticRepository.addImport(counts.userId(), counts.totalRows(), counts.successRows(),
                counts.failedRows(), counts.importedAt(), now);
        // Audit (OWASP A09) ghi owner trong DB — không phải userId của payload. Lỗi commit sau dòng này → exception
        // thoát khỏi listener (có log của container) và message được giao lại.
        log.info("Import {} added to import statistics of user {}", importId, counts.userId());
        // Gửi sau khi transaction này commit; message trùng (ALREADY_PROCESSED) / rollback → không có event
        dashboardEventService.importProcessed();
        return ApplyResult.APPLIED;
    }

    @Transactional(readOnly = true)
    public Optional<UserImportStatistic> findByUserId(Long userId) {
        return userImportStatisticRepository.findById(userId);
    }

    public enum ApplyResult {
        /** Đã cộng vào thống kê. */
        APPLIED,
        /** Lần import đã được cộng trước đó (message trùng) — không làm gì. */
        ALREADY_PROCESSED,
        /** Không có lịch sử import với id này — không làm gì. */
        NOT_FOUND
    }
}
