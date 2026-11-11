package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.enums.ImportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImportHistoryRepository extends JpaRepository<ImportHistory, Long> {

    /**
     * Chuyển trạng thái {@code from → to} chỉ khi bản ghi đang ở {@code from} — cơ sở idempotency của listener:
     * UPDATE giữ khoá dòng tới hết transaction, consumer thứ 2 nhận trùng message phải chờ rồi thấy trạng thái đã đổi
     * → 0 dòng.
     *
     * @param now giờ UTC ghi vào {@code processed_at} và {@code updated_at}
     * @return 1 nếu đã chuyển; 0 nếu đã ở trạng thái khác (đã xử lý) hoặc không tồn tại
     */
    @Modifying
    @Query("""
            UPDATE ImportHistory h SET h.status = :to, h.processedAt = :now, h.updatedAt = :now
            WHERE h.id = :id AND h.status = :from""")
    int transitionStatus(@Param("id") Long id, @Param("from") ImportStatus from, @Param("to") ImportStatus to,
                         @Param("now") LocalDateTime now);

    /** Số liệu của 1 lần import để cộng vào thống kê — projection, không nạp entity. */
    @Query("""
            SELECT new com.huuhv.socialanalytics.dto.response.ImportCounts(
                h.user.id, h.totalRows, h.createdRows + h.updatedRows, h.failedRows, h.createdAt)
            FROM ImportHistory h WHERE h.id = :id""")
    Optional<ImportCounts> findCountsById(@Param("id") Long id);
}
