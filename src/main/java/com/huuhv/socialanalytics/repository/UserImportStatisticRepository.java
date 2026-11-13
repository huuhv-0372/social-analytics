package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.UserImportStatistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface UserImportStatisticRepository extends JpaRepository<UserImportStatistic, Long> {

    /**
     * Cộng 1 lần import vào thống kê của user — tạo dòng nếu chưa có. 1 câu {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * (nguyên tử, khoá theo {@code user_id}) thay vì đọc-sửa-ghi: 2 consumer cộng cùng user không làm mất số liệu.
     * Alias {@code AS new} (MySQL 8.0.19+) thay cho hàm {@code VALUES()} đã deprecated.
     *
     * @param importedAt thời điểm của lần import (UTC); {@code last_import_at} giữ giá trị lớn nhất vì message có thể
     *                   được xử lý không theo thứ tự
     * @param now        giờ UTC ghi vào {@code updated_at}
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_import_statistics
                (user_id, total_imports, total_rows, success_rows, failed_rows, last_import_at, updated_at)
            VALUES (:userId, 1, :totalRows, :successRows, :failedRows, :importedAt, :now) AS new
            ON DUPLICATE KEY UPDATE
                total_imports  = user_import_statistics.total_imports + 1,
                total_rows     = user_import_statistics.total_rows + new.total_rows,
                success_rows   = user_import_statistics.success_rows + new.success_rows,
                failed_rows    = user_import_statistics.failed_rows + new.failed_rows,
                last_import_at = GREATEST(user_import_statistics.last_import_at, new.last_import_at),
                updated_at     = new.updated_at""", nativeQuery = true)
    void addImport(@Param("userId") Long userId,
                   @Param("totalRows") int totalRows,
                   @Param("successRows") int successRows,
                   @Param("failedRows") int failedRows,
                   @Param("importedAt") LocalDateTime importedAt,
                   @Param("now") LocalDateTime now);
}
