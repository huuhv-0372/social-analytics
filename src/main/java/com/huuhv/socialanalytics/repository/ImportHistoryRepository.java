package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.ImportHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImportHistoryRepository extends JpaRepository<ImportHistory, Long> {

    /**
     * Đánh dấu {@code PROCESSED} nếu chưa được xử lý ({@code PENDING}, hoặc {@code DEAD_LETTERED} khi message được gửi
     * lại sau khi vào DLQ) — cơ sở idempotency của listener: UPDATE giữ khoá dòng tới hết transaction, consumer
     * thứ 2 nhận trùng message phải chờ rồi thấy đã {@code PROCESSED} → 0 dòng.
     *
     * @param now giờ UTC ghi vào {@code processed_at} và {@code updated_at}
     * @return 1 nếu vừa đánh dấu; 0 nếu đã {@code PROCESSED} hoặc không tồn tại
     */
    @Modifying
    @Query("""
            UPDATE ImportHistory h
            SET h.status = com.huuhv.socialanalytics.enums.ImportStatus.PROCESSED,
                h.processedAt = :now, h.updatedAt = :now
            WHERE h.id = :id AND h.status <> com.huuhv.socialanalytics.enums.ImportStatus.PROCESSED""")
    int markProcessed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Đánh dấu {@code DEAD_LETTERED} khi message vào DLQ — chỉ từ {@code PENDING}: lần import đã {@code PROCESSED}
     * (vd bản sao message bị lỗi sau khi bản gốc đã xử lý xong) giữ nguyên trạng thái. Không ghi {@code processed_at}.
     *
     * @param now giờ UTC ghi vào {@code updated_at}
     * @return 1 nếu vừa đánh dấu; 0 nếu không ở {@code PENDING} hoặc không tồn tại
     */
    @Modifying
    @Query("""
            UPDATE ImportHistory h
            SET h.status = com.huuhv.socialanalytics.enums.ImportStatus.DEAD_LETTERED, h.updatedAt = :now
            WHERE h.id = :id AND h.status = com.huuhv.socialanalytics.enums.ImportStatus.PENDING""")
    int markDeadLettered(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Số liệu của 1 lần import để cộng vào thống kê — projection, không nạp entity. */
    @Query("""
            SELECT new com.huuhv.socialanalytics.dto.response.ImportCounts(
                h.user.id, h.totalRows, h.createdRows + h.updatedRows, h.failedRows, h.createdAt)
            FROM ImportHistory h WHERE h.id = :id""")
    Optional<ImportCounts> findCountsById(@Param("id") Long id);
}
