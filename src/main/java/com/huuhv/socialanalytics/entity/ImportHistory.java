package com.huuhv.socialanalytics.entity;

import com.huuhv.socialanalytics.enums.ImportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 1 lần import Excel đã ghi DB. {@code id} là {@code importId} của message {@code IMPORT_COMPLETED}.
 */
@Entity
@Table(name = "import_histories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportHistory extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_rows", nullable = false)
    private int createdRows;

    @Column(name = "updated_rows", nullable = false)
    private int updatedRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    // Chỉ gán lúc tạo (PENDING); đổi trạng thái bằng UPDATE có điều kiện ở ImportHistoryRepository — updatable = false
    // để flush của entity đã nạp từ trước không ghi đè trạng thái do listener đặt
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private ImportStatus status;

    // Giờ UTC. Chỉ listener ghi (UPDATE có điều kiện ở ImportHistoryRepository) → entity không ghi đè giá trị đó
    @Column(name = "processed_at", insertable = false, updatable = false)
    private LocalDateTime processedAt;
}
