package com.huuhv.socialanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Snapshot số liệu tương tác tại thời điểm {@code collectedAt}. Mỗi lần crawl tạo bản ghi mới, không cập nhật bản cũ.
 */
@Entity
@Table(name = "social_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "likes", nullable = false)
    private long likes;

    @Column(name = "shares", nullable = false)
    private long shares;

    @Column(name = "comments", nullable = false)
    private long comments;

    @Column(name = "followers", nullable = false)
    private long followers;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
