package com.huuhv.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Thống kê import tổng hợp của 1 user ({@code user_import_statistics}). Chỉ đọc qua JPA ({@link Immutable}): giá trị
 * được cộng dồn nguyên tử bằng native upsert {@code UserImportStatisticRepository#addImport}, không load-sửa-lưu
 * (2 consumer song song sẽ ghi đè lẫn nhau). Không map quan hệ tới {@link User} — chỉ cần id.
 */
@Entity
@Immutable
@Table(name = "user_import_statistics")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserImportStatistic {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "total_imports", nullable = false)
    private long totalImports;

    @Column(name = "total_rows", nullable = false)
    private long totalRows;

    @Column(name = "success_rows", nullable = false)
    private long successRows;

    @Column(name = "failed_rows", nullable = false)
    private long failedRows;

    // Giờ UTC
    @Column(name = "last_import_at", nullable = false)
    private LocalDateTime lastImportAt;

    // Giờ UTC
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
