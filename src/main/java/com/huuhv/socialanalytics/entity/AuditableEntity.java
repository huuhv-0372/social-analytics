package com.huuhv.socialanalytics.entity;

import com.huuhv.socialanalytics.util.UtcDateTimes;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Cột created_at / updated_at gán qua JPA lifecycle callback (không cần bật JPA Auditing), giờ <b>UTC</b>
 * ({@link UtcDateTimes}). Không dùng {@code @CreationTimestamp}/{@code @UpdateTimestamp} vì Hibernate sinh
 * {@code LocalDateTime} theo múi giờ JVM — lệch giữa container (UTC) và máy dev (+07) khi dùng chung DB.
 */
@Getter
@MappedSuperclass
public abstract class AuditableEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = UtcDateTimes.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = UtcDateTimes.now();
    }
}
